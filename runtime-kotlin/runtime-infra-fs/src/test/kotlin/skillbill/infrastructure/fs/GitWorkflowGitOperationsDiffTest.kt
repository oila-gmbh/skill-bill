package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkflowGitOperationsDiffTest {
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
    val branch = git(repoRoot, "branch", "--show-current")
    val baseline = ops.captureGoalSubtaskReviewBaseline(repoRoot, branch)

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
      branch,
    )

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertTrue(reviewText.startsWith("scope-fingerprint:"), reviewText)
    assertFalse("committed" in reviewText)
    assertFalse("owned content" in reviewText)
    assertFalse("preexisting.tmp" in reviewText)
  }

  /**
   * WE-4860 subtask 3 retired a module: 1.1MB of its 1.7MB delta was the bodies of 170 deleted
   * files. Blocking there refuses to review the additions and modifications too, so an over-bound
   * delta keeps every surviving patch in full and reduces the deletions to a named manifest.
   */
  @Test
  fun `an oversized worktree still resolves as a scope fingerprint without inlining bodies`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-elided-deletions")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("retired.txt"), "retired body line\n".repeat(70_000))
    Files.writeString(repoRoot.resolve("kept.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    val ops = GitWorkflowGitOperations()
    val branch = git(repoRoot, "branch", "--show-current")
    val baseline = ops.captureGoalSubtaskReviewBaseline(repoRoot, branch)
    assertTrue(baseline.ok, baseline.error)
    Files.delete(repoRoot.resolve("retired.txt"))
    Files.writeString(repoRoot.resolve("kept.txt"), "base\nsurviving edit\n")
    git(repoRoot, "add", "-A")

    val input = ops.buildGoalSubtaskReviewInput(repoRoot, requireNotNull(baseline.baseline), branch)

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertTrue(reviewText.startsWith("scope-fingerprint:"), reviewText)
    assertFalse("retired body line" in reviewText)
    assertFalse("surviving edit" in reviewText)
  }

  @Test
  fun `a small deletion still resolves as a scope fingerprint`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-small-deletion")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("retired.txt"), "retired body line\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    val ops = GitWorkflowGitOperations()
    val branch = git(repoRoot, "branch", "--show-current")
    val baseline = ops.captureGoalSubtaskReviewBaseline(repoRoot, branch)
    assertTrue(baseline.ok, baseline.error)
    Files.delete(repoRoot.resolve("retired.txt"))
    git(repoRoot, "add", "-A")

    val input = ops.buildGoalSubtaskReviewInput(repoRoot, requireNotNull(baseline.baseline), branch)

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertTrue(reviewText.startsWith("scope-fingerprint:"), reviewText)
    assertFalse("retired body line" in reviewText)
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
  fun `goal review baseline capture accepts staged tracked changes`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-baseline-staged")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "staged\n")
    git(repoRoot, "add", "tracked.txt")

    val result = GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(
      repoRoot,
      git(repoRoot, "branch", "--show-current"),
    )

    assertTrue(result.ok, result.error)
    assertEquals(git(repoRoot, "rev-parse", "HEAD"), requireNotNull(result.baseline).reviewBaseSha)
  }
}
