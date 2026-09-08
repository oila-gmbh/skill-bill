package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Operates on real temporary git repositories, never on mocked git output: the whole point of these
 * assertions is the exact index tree, which only real plumbing can produce.
 */
class GitScopedStagingOperationsTest {
  private lateinit var repo: Path

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-scoped-staging")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    write("tracked/Base.kt", "base\n")
    git("add", "-A")
    git("commit", "-m", "base")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `stagePaths expands an untracked directory pathspec into its files`() {
    write("owned/nested/One.kt", "one\n")
    write("owned/nested/Two.kt", "two\n")
    write("foreign/Skip.kt", "skip\n")

    val result = GitScopedStagingOperations.stagePaths(repo, listOf("owned/nested/"))

    assertTrue(result.ok, result.error)
    val index = indexSnapshot().keys
    assertTrue("owned/nested/One.kt" in index)
    assertTrue("owned/nested/Two.kt" in index)
    assertFalse("foreign/Skip.kt" in index)
  }

  @Test
  fun `stagePaths stages exactly the listed paths and leaves foreign entries index-identical`() {
    write("owned/Owned.kt", "owned\n")
    write("foreign/Untracked.kt", "untracked\n")
    write("tracked/Base.kt", "modified by someone else\n")
    write("foreign/Staged.kt", "staged by someone else\n")
    git("add", "--", "foreign/Staged.kt")

    val before = indexSnapshot()
    val foreignBytes = read("foreign/Untracked.kt")
    val trackedBytes = read("tracked/Base.kt")

    val result = GitScopedStagingOperations.stagePaths(repo, listOf("owned/Owned.kt"))

    assertTrue(result.ok, result.error)
    val after = indexSnapshot()
    assertEquals(
      setOf("owned/Owned.kt"),
      after.keys - before.keys,
      "only the listed path may be added to the index",
    )
    (before.keys).forEach { path ->
      assertEquals(before[path], after[path], "pre-existing index entry for '$path' must be unchanged")
    }
    assertFalse("tracked/Base.kt" in (after.keys - before.keys))
    assertEquals(before["tracked/Base.kt"], after["tracked/Base.kt"], "a foreign unstaged edit must not be staged")
    assertContentEquals(foreignBytes, read("foreign/Untracked.kt"))
    assertContentEquals(trackedBytes, read("tracked/Base.kt"))
  }

  @Test
  fun `stagePaths stages a deletion of an owned path rather than silently skipping it`() {
    Files.delete(repo.resolve("tracked/Base.kt"))

    val result = GitScopedStagingOperations.stagePaths(repo, listOf("tracked/Base.kt"))

    assertTrue(result.ok, result.error)
    assertFalse("tracked/Base.kt" in indexSnapshot().keys, "a deleted owned path must be staged as a deletion")
  }

  @Test
  fun `stagePaths treats an already-staged deletion as a no-op and still stages live owned paths`() {
    // A checkpoint owns a deletion that a prior attempt already staged: the path is gone from both the
    // worktree and the index, so its pathspec matches nothing and `git add --all` would abort the whole
    // batch with exit 128. The live owned path in the same batch must still be staged.
    Files.delete(repo.resolve("tracked/Base.kt"))
    git("add", "--", "tracked/Base.kt")
    write("owned/Live.kt", "owned\n")

    val result = GitScopedStagingOperations.stagePaths(repo, listOf("tracked/Base.kt", "owned/Live.kt"))

    assertTrue(result.ok, result.error)
    assertFalse("tracked/Base.kt" in indexSnapshot().keys, "an already-staged deletion must remain staged")
    assertTrue("owned/Live.kt" in indexSnapshot().keys, "a live owned path in the same batch must still be staged")
  }

  @Test
  fun `stagePaths skips an untracked ignored path and still stages the rest of the inventory`() {
    write(".gitignore", "**/agent/\n")
    git("add", "--", ".gitignore")
    git("commit", "-m", "ignore agent dirs")
    write("owned/Live.kt", "owned\n")
    write("feature/sitejournals/agent/history.md", "boundary history\n")

    val result = GitScopedStagingOperations.stagePaths(
      repo,
      listOf("owned/Live.kt", "feature/sitejournals/agent/history.md"),
    )

    assertTrue(result.ok, result.error)
    assertTrue("owned/Live.kt" in indexSnapshot().keys)
    assertFalse("feature/sitejournals/agent/history.md" in indexSnapshot().keys)
  }

  @Test
  fun `stagePaths still stages a tracked path that matches an ignore pattern`() {
    write("tracked/secret.md", "secret\n")
    git("add", "--", "tracked/secret.md")
    git("commit", "-m", "track secret")
    write(".gitignore", "tracked/secret.md\n")
    git("add", "--", ".gitignore")
    git("commit", "-m", "ignore secret")
    write("tracked/secret.md", "updated\n")

    val result = GitScopedStagingOperations.stagePaths(repo, listOf("tracked/secret.md"))

    assertTrue(result.ok, result.error)
    val indexSha = indexSnapshot()["tracked/secret.md"]?.split(' ')?.getOrNull(1)
    val worktreeSha = runGitCommand(repo, "hash-object", "--", "tracked/secret.md").value.orEmpty().trim()
    assertEquals(worktreeSha, indexSha, "a tracked ignored path must still receive the worktree update")
  }

  @Test
  fun `stagePaths round-trips paths carrying spaces and non-ASCII bytes`() {
    write("owned/a file with spaces.kt", "spaces\n")
    write("owned/ünïcødé.kt", "unicode\n")

    val paths = listOf("owned/a file with spaces.kt", "owned/ünïcødé.kt")
    val result = GitScopedStagingOperations.stagePaths(repo, paths)

    assertTrue(result.ok, result.error)
    assertTrue(indexSnapshot().keys.containsAll(paths), "quoted and non-ASCII paths must survive unchanged")
  }

  @Test
  fun `restoreIndexState after a staging failure restores the index byte-for-byte`() {
    write("owned/Owned.kt", "owned\n")
    write("tracked/Base.kt", "edited\n")
    git("add", "--", "tracked/Base.kt")
    val owned = listOf("owned/Owned.kt", "tracked/Base.kt")

    val snapshot = GitScopedStagingOperations.captureIndexState(repo, owned)
    assertTrue(snapshot.ok, snapshot.error)
    val before = indexSnapshot()

    // Model a checkpoint that staged and then failed before committing.
    assertTrue(GitScopedStagingOperations.stagePaths(repo, owned).ok)
    assertTrue("owned/Owned.kt" in indexSnapshot().keys, "precondition: staging actually mutated the index")
    val worktreeBefore = read("owned/Owned.kt")

    val restored = GitScopedStagingOperations.restoreIndexState(repo, owned, snapshot.value.orEmpty())

    assertTrue(restored.ok, restored.error)
    assertEquals(before, indexSnapshot(), "the pre-checkpoint index must be restored exactly")
    assertFalse(
      "owned/Owned.kt" in indexSnapshot().keys,
      "a path absent from the snapshot must be removed from the index, not left partially staged",
    )
    assertContentEquals(worktreeBefore, read("owned/Owned.kt"))
  }

  @Test
  fun `restoreIndexState leaves foreign index entries untouched`() {
    write("owned/Owned.kt", "owned\n")
    write("foreign/Staged.kt", "foreign\n")
    git("add", "--", "foreign/Staged.kt")
    val owned = listOf("owned/Owned.kt")

    val snapshot = GitScopedStagingOperations.captureIndexState(repo, owned)
    val foreignEntryBefore = indexSnapshot()["foreign/Staged.kt"]
    assertTrue(GitScopedStagingOperations.stagePaths(repo, owned).ok)

    assertTrue(GitScopedStagingOperations.restoreIndexState(repo, owned, snapshot.value.orEmpty()).ok)

    assertEquals(foreignEntryBefore, indexSnapshot()["foreign/Staged.kt"])
  }

  @Test
  fun `captureIndexState reports only the requested paths`() {
    write("owned/Owned.kt", "owned\n")
    write("foreign/Staged.kt", "foreign\n")
    git("add", "-A")

    val snapshot = GitScopedStagingOperations.captureIndexState(repo, listOf("owned/Owned.kt"))

    assertTrue(snapshot.ok, snapshot.error)
    val paths = snapshot.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
      .map { it.substringAfter('\t') }
    assertEquals(listOf("owned/Owned.kt"), paths)
  }

  @Test
  fun `stagedPaths reports the pre-checkpoint index against HEAD`() {
    write("foreign/Staged.kt", "foreign\n")
    git("add", "--", "foreign/Staged.kt")
    write("owned/Unstaged.kt", "unstaged\n")

    val staged = GitScopedStagingOperations.stagedPaths(repo)

    assertTrue(staged.ok, staged.error)
    assertEquals(
      listOf("foreign/Staged.kt"),
      staged.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank),
    )
  }

  @Test
  fun `an empty inventory stages nothing and reports success`() {
    val before = indexSnapshot()

    assertTrue(GitScopedStagingOperations.stagePaths(repo, emptyList()).ok)

    assertEquals(before, indexSnapshot())
  }

  // AC-005: content identity is what distinguishes "this phase wrote it" from "someone else did".
  @Test
  fun `pathContentIdentities reports one identity per present path and changes when content changes`() {
    write("owned/Owned.kt", "owned\n")
    write("owned/Spaced Path.kt", "spaced\n")

    val paths = listOf("owned/Owned.kt", "owned/Spaced Path.kt", "owned/Absent.kt")
    val first = GitScopedStagingOperations.pathContentIdentities(repo, paths)
    assertTrue(first.ok, first.error)
    val identities = contentIdentities(first.value.orEmpty())
    assertEquals(setOf("owned/Owned.kt", "owned/Spaced Path.kt"), identities.keys)

    write("owned/Owned.kt", "edited by someone else\n")
    val second = contentIdentities(
      GitScopedStagingOperations.pathContentIdentities(repo, paths).value.orEmpty(),
    )

    assertTrue(
      second["owned/Owned.kt"] != identities["owned/Owned.kt"],
      "a concurrent edit must change the reported identity",
    )
    assertEquals(
      identities["owned/Spaced Path.kt"],
      second["owned/Spaced Path.kt"],
      "an untouched path keeps its identity, whatever characters it carries",
    )
  }

  private fun contentIdentities(raw: String): Map<String, String> = raw
    .split(GIT_NUL)
    .filter(String::isNotBlank)
    .associate { record -> record.substringAfter('\t') to record.substringBefore('\t') }

  private fun indexSnapshot(): Map<String, String> = runGitCommand(repo, "ls-files", "--stage", "-z")
    .value.orEmpty()
    .split(GIT_NUL)
    .filter(String::isNotBlank)
    .associate { entry -> entry.substringAfter('\t') to entry.substringBefore('\t') }

  private fun write(relative: String, content: String) {
    val target = repo.resolve(relative)
    target.parent?.createDirectories()
    target.writeText(content)
  }

  private fun read(relative: String): ByteArray = repo.resolve(relative).readBytes()

  private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
    assertTrue(expected.contentEquals(actual), "file content must be byte-for-byte unchanged")
  }

  private fun git(vararg args: String) {
    val result = runGitCommand(repo, *args)
    assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.error}")
  }
}
