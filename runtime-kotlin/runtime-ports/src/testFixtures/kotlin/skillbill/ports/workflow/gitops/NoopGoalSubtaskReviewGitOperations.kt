package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import java.nio.file.Path

internal object NoopGoalSubtaskReviewGitOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult {
    return if (expectedBranch.isBlank()) {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.ERROR,
        error = "Goal-subtask durable child branch is required.",
      )
    } else {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = GoalSubtaskReviewBaseline(
          reviewBaseSha = "0".repeat(NOOP_REVIEW_BASE_SHA_LENGTH),
          baselineUntrackedPaths = emptyList(),
        ),
      )
    }
  }

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    return GoalSubtaskReviewInputResult(
      status = WorkflowGitOperationStatus.OK,
      input = GoalSubtaskReviewInput(
        reviewBaseSha = baseline.reviewBaseSha,
        currentHeadSha = baseline.reviewBaseSha,
        trackedDelta = "",
        ownedUntrackedPatches = "",
      ),
    )
  }

  override fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    return if (expectedBranch.isBlank()) {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.ERROR,
        error = "Goal-subtask durable child branch is required.",
      )
    } else {
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = request.toRecoveredBaseline(request.unreachableSha),
      )
    }
  }
}
