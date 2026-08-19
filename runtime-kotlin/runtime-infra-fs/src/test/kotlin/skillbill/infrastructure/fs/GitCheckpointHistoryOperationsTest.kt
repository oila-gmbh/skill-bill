package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHECKPOINT_PREFIX = "refs/skill-bill/checkpoints/"

/**
 * Real temporary git repositories only: the behaviour under test is git's own amend and ref plumbing.
 */
class GitCheckpointHistoryOperationsTest {
  private lateinit var repo: Path

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-checkpoint-history")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    write("owned/Base.kt", "base\n")
    write("foreign/Other.kt", "other\n")
    git("add", "-A")
    git("commit", "-m", "base")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  // An implementation reaching for `commit -a` or `git add` would sweep the unstaged edit into the
  // amended commit, silently publishing work the checkpoint does not own.
  @Test
  fun `amend commits only the index and leaves an unstaged edit unstaged`() {
    val before = head()
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")
    write("foreign/Other.kt", "edited by someone else\n")

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, before)

    assertTrue(amended.ok, amended.error)
    assertEquals(head(), amended.value.trim())
    assertTrue(head() != before, "amend must rewrite HEAD")
    assertEquals("amended\n", showAtHead("owned/Base.kt"))
    assertEquals("other\n", showAtHead("foreign/Other.kt"))
    assertEquals(
      listOf(" M foreign/Other.kt"),
      runGitCommand(repo, "status", "--porcelain").value.orEmpty().lines().filter(String::isNotBlank),
    )
  }

  // `git commit --amend --no-edit` happily succeeds on an empty index, rewriting the previous
  // commit's sha and orphaning every checkpoint identity that pointed at it.
  @Test
  fun `amend with an empty index fails and leaves HEAD unchanged`() {
    val before = head()

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, before)

    assertFalse(amended.ok)
    assertEquals(before, head())
  }

  // The same-branch amend hazard: subtask N's first checkpoint must not destroy subtask N-1's commit.
  @Test
  fun `amend refuses when HEAD is not the caller-owned commit`() {
    val before = head()
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, "0".repeat(before.length))

    assertFalse(amended.ok)
    assertEquals(before, head())
  }

  @Test
  fun `a checkpoint ref round-trips write, resolve, list and idempotent delete`() {
    val sha = head()
    val ref = "${CHECKPOINT_PREFIX}subtask-1/städte-checkpoint"

    assertTrue(GitCheckpointHistoryOperations.updateRef(repo, CHECKPOINT_PREFIX, ref, sha).ok)
    assertEquals(sha, GitCheckpointHistoryOperations.resolveRef(repo, CHECKPOINT_PREFIX, ref).value.trim())
    assertEquals(mapOf(ref to sha), listedRefs())

    assertTrue(GitCheckpointHistoryOperations.deleteRef(repo, CHECKPOINT_PREFIX, ref).ok)
    assertTrue(
      GitCheckpointHistoryOperations.deleteRef(repo, CHECKPOINT_PREFIX, ref).ok,
      "a repeated delete must stay idempotent so an interrupted prune can re-run",
    )
    assertEquals(emptyMap(), listedRefs())
    val absent = GitCheckpointHistoryOperations.resolveRef(repo, CHECKPOINT_PREFIX, ref)
    assertTrue(absent.ok, "an absent ref must resolve ok so callers can tell it from a failed lookup")
    assertEquals("", absent.value.trim())
  }

  // A namespace escape would move or delete a real branch ref, destroying delivered work.
  @Test
  fun `ref operations reject a name outside the namespace prefix and leave it untouched`() {
    val sha = head()

    val update = GitCheckpointHistoryOperations.updateRef(repo, CHECKPOINT_PREFIX, "refs/heads/main", sha)
    val delete = GitCheckpointHistoryOperations.deleteRef(repo, CHECKPOINT_PREFIX, "refs/heads/main")

    val siblingPrefix = GitCheckpointHistoryOperations.updateRef(
      repo,
      CHECKPOINT_PREFIX,
      CHECKPOINT_PREFIX.trimEnd('/') + "-tmp/a",
      sha,
    )

    assertFalse(update.ok)
    assertFalse(delete.ok)
    assertFalse(siblingPrefix.ok)
    assertEquals(sha, runGitCommand(repo, "rev-parse", "refs/heads/main").value.trim())
    assertEquals(emptyMap(), listedRefs())
  }

  // AC-006/AC-007: the amend discards a commit from branch history, so the ref written first is the
  // only thing that can still reach it. A ref that did not survive the amend means the runtime threw
  // away a checkpoint nothing can recover.
  @Test
  fun `a pre-amend ref keeps the discarded commit reachable while git log never lists it`() {
    val preAmend = head()
    val ref = "${CHECKPOINT_PREFIX}SKILL-190/3/0"
    assertTrue(GitCheckpointHistoryOperations.updateRef(repo, CHECKPOINT_PREFIX, ref, preAmend).ok)
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")

    assertTrue(GitCheckpointHistoryOperations.amendHeadCommit(repo, preAmend).ok)

    assertTrue(head() != preAmend, "the amend must have rewritten HEAD")
    assertEquals(
      preAmend,
      GitCheckpointHistoryOperations.resolveRef(repo, CHECKPOINT_PREFIX, ref).value.trim(),
      "the pre-amend commit must stay reachable through its checkpoint ref",
    )
    assertEquals(
      listOf(head()),
      runGitCommand(repo, "log", "--format=%H").value.orEmpty().lines().filter(String::isNotBlank),
      "checkpoint refs must not appear in branch history",
    )
  }

  // The create-or-amend fallback reads the subtask trailer off HEAD, so the whole body must come back.
  @Test
  fun `the HEAD commit message read returns the full body including its trailer`() {
    write("owned/Base.kt", "trailered\n")
    git("add", "--", "owned/Base.kt")
    git("commit", "-m", "SKILL-190: subtask work\n\nphase=audit generation=0\n\nSkill-Bill-Subtask: SKILL-190/3\n")

    val message = GitCheckpointHistoryOperations.headCommitMessage(repo)

    assertTrue(message.ok, message.error)
    assertContains(message.value, "Skill-Bill-Subtask: SKILL-190/3")
    assertContains(message.value, "phase=audit generation=0")
  }

  private fun listedRefs(): Map<String, String> = GitCheckpointHistoryOperations
    .listRefs(repo, CHECKPOINT_PREFIX).value.orEmpty()
    .split(GIT_NUL)
    .filter(String::isNotBlank)
    .chunked(2)
    .associate { (objectName, refName) -> refName.trim() to objectName.trim() }

  private fun head(): String = runGitCommand(repo, "rev-parse", "HEAD").value.orEmpty().trim()

  private fun showAtHead(relative: String): String =
    runGitProcess(repo, listOf("show", "HEAD:$relative")).output + "\n"

  private fun write(relative: String, content: String) {
    val target = repo.resolve(relative)
    target.parent?.createDirectories()
    target.writeText(content)
  }

  private fun git(vararg args: String) {
    val result = runGitCommand(repo, *args)
    check(result.ok) { "git ${args.joinToString(" ")} failed: ${result.error}" }
  }
}
