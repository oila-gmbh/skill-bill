package skillbill.infrastructure.fs

import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkflowGitOperationsTest {
  @Test
  fun `create commit includes staged decomposition manifest projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-workflow")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("runtime.txt"), "runtime change\n")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "contract_version: \"0.1\"\n")
    git(repoRoot, "add", ".")

    val result = GitWorkflowGitOperations().createCommit(repoRoot, "SKILL-52 subtask 1: demo")

    assertTrue(result.ok, result.error)
    val committedFiles = git(repoRoot, "show", "--name-only", "--format=", "HEAD")
    assertContains(committedFiles, "runtime.txt")
    assertContains(committedFiles, ".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    assertEquals("", git(repoRoot, "status", "--short"))
  }

  @Test
  fun `branch exists reports presence without creating the branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-branch-exists")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "branch", "feat/present")
    val ops = GitWorkflowGitOperations()

    val present = ops.branchExists(repoRoot, "feat/present")
    val absent = ops.branchExists(repoRoot, "feat/absent")

    assertTrue(present.ok, present.error)
    assertEquals("true", present.value)
    assertTrue(absent.ok, absent.error)
    assertEquals("false", absent.value)
    assertFalse(git(repoRoot, "branch", "--list", "feat/absent").contains("feat/absent"))
  }

  @Test
  fun `branch exists reports fatal git failures as errors instead of absent branches`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-branch-exists-not-repo")

    val result = GitWorkflowGitOperations().branchExists(repoRoot, "feat/persisted")

    assertFalse(result.ok)
    assertContains(result.error, "git rev-parse")
  }

  @Test
  fun `worktree activity summarizes changed files and diff stat`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-activity")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\n")
    Files.writeString(repoRoot.resolve("new.txt"), "new\n")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(2, result.changedFileSummary?.total)
    assertEquals(1, result.changedFileSummary?.modified)
    assertEquals(1, result.changedFileSummary?.untracked)
    assertEquals(1, result.diffStat?.filesChanged)
    assertEquals(1, result.diffStat?.insertions)
  }

  @Test
  fun `worktree activity reports clean repository with zero activity`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-clean")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(0, result.changedFileSummary?.total)
    assertEquals(0, result.changedFileSummary?.modified)
    assertEquals(0, result.changedFileSummary?.renamed)
    assertEquals(0, result.changedFileSummary?.deleted)
    assertEquals(0, result.changedFileSummary?.untracked)
    assertEquals(emptyList(), result.changedFileSummary?.samplePaths)
    assertEquals(0, result.diffStat?.filesChanged)
    assertEquals(0, result.diffStat?.insertions)
    assertEquals(0, result.diffStat?.deletions)
  }

  @Test
  fun `worktree activity summarizes modified renamed deleted and untracked files`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-status-kinds")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("modified.txt"), "one\n")
    Files.writeString(repoRoot.resolve("rename-before.txt"), "rename me\n")
    Files.writeString(repoRoot.resolve("deleted.txt"), "delete me\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("modified.txt"), "one\ntwo\n")
    git(repoRoot, "mv", "rename-before.txt", "rename-after.txt")
    Files.delete(repoRoot.resolve("deleted.txt"))
    Files.writeString(repoRoot.resolve("untracked.txt"), "new\n")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(4, result.changedFileSummary?.total)
    assertEquals(1, result.changedFileSummary?.modified)
    assertEquals(1, result.changedFileSummary?.renamed)
    assertEquals(1, result.changedFileSummary?.deleted)
    assertEquals(1, result.changedFileSummary?.untracked)
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "modified.txt")
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "rename-after.txt")
    assertContains(result.changedFileSummary?.samplePaths.orEmpty(), "untracked.txt")
    assertEquals(3, result.diffStat?.filesChanged)
    assertEquals(1, result.diffStat?.insertions)
    assertEquals(1, result.diffStat?.deletions)
  }

  @Test
  fun `worktree activity drains large status and numstat output before waiting`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-large-drain")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    val changedFiles = 5_000
    (1..changedFiles).forEach { index ->
      Files.writeString(repoRoot.resolve("tracked-$index.txt"), "before\n")
    }
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    (1..changedFiles).forEach { index ->
      Files.writeString(repoRoot.resolve("tracked-$index.txt"), "before\nafter\n")
    }

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(changedFiles, result.changedFileSummary?.total)
    assertEquals(changedFiles, result.changedFileSummary?.modified)
    assertEquals(changedFiles, result.diffStat?.filesChanged)
    assertEquals(changedFiles, result.diffStat?.insertions)
  }

  @Test
  fun `selected diff hunks are path scoped and bounded across staged and unstaged changes`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-hunks")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\nthree\n")
    Files.writeString(repoRoot.resolve("other.txt"), "alpha\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\nTWO\nthree\n")
    git(repoRoot, "add", "tracked.txt")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\nTWO\nTHREE\n")
    Files.writeString(repoRoot.resolve("other.txt"), "beta\n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(paths = listOf("tracked.txt"), maxHunks = 1, maxLines = 10, maxBytes = 400),
    )

    assertTrue(result.ok, result.error)
    assertEquals(1, result.selectedDiffHunks.hunks.size)
    assertEquals(true, result.selectedDiffHunks.truncated)
    assertEquals("tracked.txt", result.selectedDiffHunks.hunks.single().path)
    assertEquals(false, result.selectedDiffHunks.hunks.single().staged)
    assertEquals(false, result.selectedDiffHunks.hunks.single().lines.any { it.contains("beta") })
  }

  @Test
  fun `selected diff does not mark exactly max hunks as truncated at eof`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-exact-max")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "ONE\ntwo\n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(
        paths = listOf("tracked.txt"),
        includeStaged = false,
        maxHunks = 1,
        maxLines = 20,
        maxBytes = 1_000,
      ),
    )

    assertTrue(result.ok, result.error)
    assertEquals(1, result.selectedDiffHunks.hunks.size)
    assertFalse(result.selectedDiffHunks.truncated)
  }

  @Test
  fun `selected diff line cap is shared across unstaged and staged hunks`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-shared-lines")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "ONE\ntwo\n")
    git(repoRoot, "add", "tracked.txt")
    Files.writeString(repoRoot.resolve("tracked.txt"), "ONE\nTWO\n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(paths = listOf("tracked.txt"), maxHunks = 4, maxLines = 3, maxBytes = 1_000),
    )

    assertTrue(result.ok, result.error)
    assertEquals(3, result.selectedDiffHunks.hunks.sumOf { it.lines.size })
    assertTrue(result.selectedDiffHunks.truncated)
  }

  @Test
  fun `selected diff preserves trailing blank and space context lines`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-preserve-lines")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "alpha\n\nbeta  \n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "ALPHA\n\nbeta  \n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(
        paths = listOf("tracked.txt"),
        includeStaged = false,
        maxHunks = 1,
        maxLines = 20,
        maxBytes = 1_000,
      ),
    )

    assertTrue(result.ok, result.error)
    assertContains(result.selectedDiffHunks.hunks.single().lines, " ")
    assertContains(result.selectedDiffHunks.hunks.single().lines, " beta  ")
  }

  @Test
  fun `selected diff returns bounded hunks for large path output`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-large")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), (1..3_000).joinToString("\n") { "line $it" } + "\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), (1..3_000).joinToString("\n") { "changed $it" } + "\n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(
        paths = listOf("tracked.txt"),
        includeStaged = false,
        maxHunks = 1,
        maxLines = 5,
        maxBytes = 200,
      ),
    )

    assertTrue(result.ok, result.error)
    assertEquals(1, result.selectedDiffHunks.hunks.size)
    assertEquals(5, result.selectedDiffHunks.hunks.single().lines.size)
    assertTrue(result.selectedDiffHunks.truncated)
  }

  @Test
  fun `selected diff truncates a single huge line within the byte budget`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-selected-huge-line")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "before\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "x".repeat(200_000) + "\n")

    val result = GitWorkflowGitOperations().selectedDiffHunks(
      repoRoot,
      WorkflowSelectedDiffHunksRequest(
        paths = listOf("tracked.txt"),
        includeStaged = false,
        maxHunks = 1,
        maxLines = 10,
        maxBytes = 24,
      ),
    )

    assertTrue(result.ok, result.error)
    val hunk = result.selectedDiffHunks.hunks.single()
    val emittedBytes = hunk.lines.sumOf { line -> line.toByteArray().size + 1 }
    assertTrue(result.selectedDiffHunks.truncated)
    assertTrue(hunk.truncated)
    assertTrue(emittedBytes <= 24, hunk.lines.joinToString("\n"))
    assertTrue(hunk.lines.any { line -> line.startsWith("+x") }, hunk.lines.joinToString("\n"))
    assertTrue(hunk.lines.none { line -> line.length > 24 }, hunk.lines.joinToString("\n"))
  }

  private fun git(repoRoot: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }
}
