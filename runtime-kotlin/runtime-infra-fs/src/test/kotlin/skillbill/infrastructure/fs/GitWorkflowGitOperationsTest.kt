package skillbill.infrastructure.fs

import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
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

  @Test
  fun `goal review input includes base to current tracked delta and only owned untracked files`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-input")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("preexisting.tmp"), "not owned\n")
    val ops = GitWorkflowGitOperations()
    val baseline = ops.captureGoalSubtaskReviewBaseline(repoRoot)

    assertTrue(baseline.ok, baseline.error)
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ncommitted\n")
    git(repoRoot, "add", "tracked.txt")
    git(repoRoot, "commit", "-m", "subtask commit")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ncommitted\nstaged\n")
    git(repoRoot, "add", "tracked.txt")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\ncommitted\nstaged\nunstaged\n")
    Files.writeString(repoRoot.resolve("owned.tmp"), "owned content\n")

    val input = ops.buildGoalSubtaskReviewInput(
      repoRoot,
      requireNotNull(baseline.baseline),
      git(repoRoot, "branch", "--show-current"),
    )

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertContains(reviewText, "committed")
    assertContains(reviewText, "staged")
    assertContains(reviewText, "unstaged")
    assertContains(reviewText, "owned.tmp")
    assertContains(reviewText, "owned content")
    assertFalse("preexisting.tmp" in reviewText)
  }

  @Test
  fun `goal review baseline capture rejects a branch other than the durable child branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-baseline-branch")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val result = GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot, "feat/another-child")

    assertFalse(result.ok)
    assertContains(result.error, "durable child branch 'feat/another-child'")
  }

  @Test
  fun `goal review input excludes committed changes from an earlier subtask`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-earlier-subtask")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("shared.txt"), "initial\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("earlier-subtask.txt"), "earlier subtask marker\n")
    git(repoRoot, "add", "earlier-subtask.txt")
    git(repoRoot, "commit", "-m", "earlier subtask")

    val baseline = requireNotNull(GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot).baseline)
    Files.writeString(repoRoot.resolve("current-subtask.txt"), "current subtask marker\n")
    git(repoRoot, "add", "current-subtask.txt")
    git(repoRoot, "commit", "-m", "current subtask")

    val input = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      baseline,
      git(repoRoot, "branch", "--show-current"),
    )

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertContains(reviewText, "current-subtask.txt")
    assertContains(reviewText, "current subtask marker")
    assertFalse("earlier-subtask.txt" in reviewText)
    assertFalse("earlier subtask marker" in reviewText)
  }

  @Test
  fun `goal review input rejects an unsafe persisted base without branch fallback`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-unsafe-base")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val result = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      GoalSubtaskReviewBaseline("f".repeat(40), emptyList()),
      "master",
    )

    assertFalse(result.ok)
    assertContains(result.error, "Persisted review base")
    assertFalse("origin/main" in result.error)
  }

  @Test
  fun `goal review input rejects a worktree on another child branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-wrong-branch")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    val originalBranch = git(repoRoot, "branch", "--show-current")
    git(repoRoot, "checkout", "-b", "feat/child-one")
    val baseline = requireNotNull(GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot).baseline)
    git(repoRoot, "checkout", originalBranch)

    val result = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(repoRoot, baseline, "feat/child-one")

    assertFalse(result.ok)
    assertContains(result.error, "durable child branch 'feat/child-one'")
  }

  private fun git(repoRoot: Path, vararg args: String): String {
    val output = runGit(repoRoot, *args)
    // Persist signing-off into the repo's own config right after init so that BOTH
    // these helper commits AND the production GitWorkflow commits under test (which
    // run their own `git commit` in this repo) skip signing. A host global
    // commit.gpgsign=true would otherwise fail every commit when gpg is absent from
    // the process environment (common on dev machines and self-hosted CI runners).
    if (args.firstOrNull() == "init") {
      runGit(repoRoot, "config", "commit.gpgsign", "false")
      runGit(repoRoot, "config", "tag.gpgsign", "false")
    }
    return output
  }

  private fun runGit(repoRoot: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }
}
