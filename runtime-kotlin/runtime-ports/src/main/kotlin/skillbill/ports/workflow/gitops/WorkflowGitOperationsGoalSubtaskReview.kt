package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import java.nio.file.Path

interface GoalSubtaskReviewGitOperations {
  fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult

  fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult

  fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
    status = "error",
    error = "Goal-subtask review baseline recovery is not supported by this git adapter.",
  )
}

interface GoalSubtaskReviewGitOperationsProvider {
  val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations
}

private object UnavailableGoalSubtaskReviewGitOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = "error",
      error = "Goal-subtask review baselines require a branch-aware git adapter.",
    )

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
    status = "error",
    error = "Goal-subtask review input requires a git adapter.",
  )

  override fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
    status = "error",
    error = "Goal-subtask review baseline recovery requires a git adapter.",
  )
}

fun WorkflowGitOperations.captureGoalSubtaskReviewBaseline(
  repoRoot: Path,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = reviewOperations().captureBaseline(repoRoot, expectedBranch)

fun WorkflowGitOperations.buildGoalSubtaskReviewInput(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalSubtaskReviewInputResult = reviewOperations().buildInput(repoRoot, baseline, expectedBranch)

fun WorkflowGitOperations.recoverGoalSubtaskReviewBaseline(
  repoRoot: Path,
  request: GoalSubtaskReviewBaselineRecoveryRequest,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = reviewOperations().recoverBaseline(repoRoot, request, expectedBranch)

private fun WorkflowGitOperations.reviewOperations(): GoalSubtaskReviewGitOperations =
  (this as? GoalSubtaskReviewGitOperationsProvider)?.goalSubtaskReviewOperations
    ?: UnavailableGoalSubtaskReviewGitOperations
