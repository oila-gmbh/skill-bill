package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
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

fun WorkflowGitOperations.captureGoalSubtaskReviewBaseline(
  repoRoot: Path,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = goalSubtaskReviewOperations.captureBaseline(repoRoot, expectedBranch)

fun WorkflowGitOperations.buildGoalSubtaskReviewInput(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalSubtaskReviewInputResult = goalSubtaskReviewOperations.buildInput(repoRoot, baseline, expectedBranch)

fun WorkflowGitOperations.recoverGoalSubtaskReviewBaseline(
  repoRoot: Path,
  request: GoalSubtaskReviewBaselineRecoveryRequest,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = goalSubtaskReviewOperations.recoverBaseline(repoRoot, request, expectedBranch)
