package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkflowGitOperationsRecoveryTest {
  @Test
  fun `goal review baseline capture accepts unstaged tracked changes`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-baseline-unstaged")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "unstaged\n")

    val result = GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(
      repoRoot,
      git(repoRoot, "branch", "--show-current"),
    )

    assertTrue(result.ok, result.error)
    assertEquals(git(repoRoot, "rev-parse", "HEAD"), requireNotNull(result.baseline).reviewBaseSha)
  }

  // Pre-existing tracked work is intentionally in scope: the review reads the whole worktree delta from
  // the base commit, so a dirty tree starts a run and the reviewer sees everything in it.
  @Test
  fun `goal review input includes tracked changes that pre-date the baseline`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-preexisting-tracked")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "pre-existing edit\n")
    val branch = git(repoRoot, "branch", "--show-current")

    val baseline = requireNotNull(
      GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot, branch).baseline,
    )
    val input = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(repoRoot, baseline, branch)

    assertTrue(input.ok, input.error)
    assertTrue(requireNotNull(input.input).trackedDelta.startsWith("scope-fingerprint:"))
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

    val branch = git(repoRoot, "branch", "--show-current")
    val baseline =
      requireNotNull(GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot, branch).baseline)
    Files.writeString(repoRoot.resolve("current-subtask.txt"), "current subtask marker\n")
    git(repoRoot, "add", "current-subtask.txt")
    git(repoRoot, "commit", "-m", "current subtask")

    val input = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      baseline,
      branch,
    )

    assertTrue(input.ok, input.error)
    val reviewText = requireNotNull(input.input).reviewText
    assertTrue(reviewText.startsWith("scope-fingerprint:"), reviewText)
    assertFalse("current subtask marker" in reviewText)
    assertFalse("earlier subtask marker" in reviewText)
  }

  @Test
  fun `goal review input rejects an unsafe persisted base without branch fallback`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-unsafe-base")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val result = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      GoalSubtaskReviewBaseline("f".repeat(40), emptyList()),
      "main",
    )

    assertFalse(result.ok)
    assertContains(result.error, "Persisted review base")
    assertFalse("origin/main" in result.error)
  }

  @Test
  fun `goal review baseline recovery reanchors rewritten child branch to branch base`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-review-recover-base")
    val remoteRoot = Files.createTempDirectory("skillbill-goal-review-recover-remote")
    git(remoteRoot, "init", "--bare")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "remote", "add", "origin", remoteRoot.toString())
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "checkout", "-b", "feat/demo")
    git(repoRoot, "push", "-u", "origin", "feat/demo")
    Files.writeString(repoRoot.resolve("tracked.txt"), "old review base\n")
    git(repoRoot, "commit", "-am", "old review base")
    val oldBaseline = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "old reviewed change\n")
    git(repoRoot, "commit", "-am", "old reviewed change")
    git(repoRoot, "reset", "--hard", "origin/feat/demo")
    Files.writeString(repoRoot.resolve("tracked.txt"), "new reviewed change\n")
    git(repoRoot, "commit", "-am", "new reviewed change")

    val unsafe = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      GoalSubtaskReviewBaseline(oldBaseline, emptyList()),
      "feat/demo",
    )
    val recovered = GitWorkflowGitOperations().recoverGoalSubtaskReviewBaseline(
      repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = oldBaseline,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = emptyList(),
      ),
      "feat/demo",
    )
    val input = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      requireNotNull(recovered.baseline),
      "feat/demo",
    )

    assertFalse(unsafe.ok)
    assertEquals(GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR, unsafe.failureReason)
    assertTrue(recovered.ok, recovered.error)
    assertTrue(input.ok, input.error)
    assertTrue(requireNotNull(input.input).reviewText.startsWith("scope-fingerprint:"))
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
    val baseline =
      requireNotNull(GitWorkflowGitOperations().captureGoalSubtaskReviewBaseline(repoRoot, "feat/child-one").baseline)
    git(repoRoot, "checkout", originalBranch)

    val result = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(repoRoot, baseline, "feat/child-one")

    assertFalse(result.ok)
    assertContains(result.error, "durable child branch 'feat/child-one'")
  }

  @Test
  fun `SKILL-15 topology recovers nearest reachable ancestor not branch base`() {
    val repoRoot = Files.createTempDirectory("skillbill-skill15-nearest-ancestor")
    val remoteRoot = Files.createTempDirectory("skillbill-skill15-nearest-remote")
    git(remoteRoot, "init", "--bare")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "remote", "add", "origin", remoteRoot.toString())
    Files.writeString(repoRoot.resolve("tracked.txt"), "root\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "root")
    git(repoRoot, "push", "-u", "origin", "main")
    val branchBase = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "checkout", "-b", "feat/skill-15")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "commit", "-am", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    // First sibling remediation checkpoint — becomes the orphaned stored base.
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-a\n")
    git(repoRoot, "commit", "-am", "sibling-a")
    val orphanedBase = git(repoRoot, "rev-parse", "HEAD")
    // Reset to parent and create the second sibling; branch tip lands here.
    git(repoRoot, "reset", "--hard", parent)
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-b\n")
    git(repoRoot, "commit", "-am", "sibling-b")
    val head = git(repoRoot, "rev-parse", "HEAD")

    val unsafe = GitWorkflowGitOperations().buildGoalSubtaskReviewInput(
      repoRoot,
      GoalSubtaskReviewBaseline(orphanedBase, emptyList()),
      "feat/skill-15",
    )
    val recovered = GitWorkflowGitOperations().recoverGoalSubtaskReviewBaseline(
      repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = orphanedBase,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = emptyList(),
      ),
      "feat/skill-15",
    )

    assertFalse(unsafe.ok)
    assertEquals(GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR, unsafe.failureReason)
    assertTrue(recovered.ok, recovered.error)
    assertEquals(parent, requireNotNull(recovered.baseline).reviewBaseSha)
    assertTrue(
      recovered.baseline!!.reviewBaseSha != branchBase,
      "nearest ancestor must be the shared parent, not origin/main branch base",
    )
    assertTrue(head != orphanedBase)
  }

  @Test
  fun `recovery with no reachable ancestor names unreachable sha and goal branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-no-reachable-ancestor")
    git(repoRoot, "init", "-b", "feat/orphan-goal")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "goal\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "goal tip")
    // Unrelated root history: orphan branch with its own root, then abandon the ref.
    git(repoRoot, "checkout", "--orphan", "unrelated-root")
    val prior = git(repoRoot, "ls-files").lines().filter { it.isNotBlank() }
    if (prior.isNotEmpty()) {
      git(repoRoot, *(listOf("rm", "-f", "--") + prior).toTypedArray())
    }
    Files.writeString(repoRoot.resolve("other.txt"), "unrelated\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "unrelated root")
    val unreachable = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "checkout", "feat/orphan-goal")
    git(repoRoot, "branch", "-D", "unrelated-root")

    val recovered = GitWorkflowGitOperations().recoverGoalSubtaskReviewBaseline(
      repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = unreachable,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = emptyList(),
      ),
      "feat/orphan-goal",
    )

    assertFalse(recovered.ok)
    assertContains(recovered.error, unreachable)
    assertContains(recovered.error, "feat/orphan-goal")
  }

  @Test
  fun `BASE_MISSING recovery falls back to branch base`() {
    val repoRoot = Files.createTempDirectory("skillbill-base-missing-fallback")
    val remoteRoot = Files.createTempDirectory("skillbill-base-missing-remote")
    git(remoteRoot, "init", "--bare")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "remote", "add", "origin", remoteRoot.toString())
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "push", "-u", "origin", "main")
    val branchBase = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "checkout", "-b", "feat/missing-base")
    Files.writeString(repoRoot.resolve("tracked.txt"), "feature\n")
    git(repoRoot, "commit", "-am", "feature")
    val missingSha = "deadbeef" + "0".repeat(32)

    val recovered = GitWorkflowGitOperations().recoverGoalSubtaskReviewBaseline(
      repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = missingSha,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_MISSING,
        baselineUntrackedPaths = emptyList(),
      ),
      "feat/missing-base",
    )

    assertTrue(recovered.ok, recovered.error)
    assertEquals(branchBase, requireNotNull(recovered.baseline).reviewBaseSha)
  }

}
