package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import java.nio.file.Path

internal object NoopGoalSubtaskReviewGitOperations : GoalSubtaskReviewGitOperations {
  private const val NAME = "NoopGoalSubtaskReviewGitOperations"

  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "captureBaseline(repoRoot=$repoRoot, expectedBranch=$expectedBranch)",
    )
    return if (expectedBranch.isBlank()) {
      GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
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
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "buildInput(repoRoot=$repoRoot, expectedBranch=$expectedBranch)")
    return GoalSubtaskReviewInputResult(
      status = "ok",
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
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "recoverBaseline(repoRoot=$repoRoot, expectedBranch=$expectedBranch)",
    )
    return if (expectedBranch.isBlank()) {
      GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = request.toRecoveredBaseline(request.unreachableSha),
      )
    }
  }
}
