package skillbill.application.goalrunner

import skillbill.ports.db.UnitOfWork
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_PASS_VERDICTS
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun GoalSubtaskReviewSummaryReducer.structuredFindings(
  output: Map<String, Any?>,
  recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
): List<StructuredGoalReviewFinding> =
  GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)

internal fun GoalSubtaskReviewSummaryReducer.reviewRunIdOf(output: Map<String, Any?>): String? =
  GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)

internal fun GoalSubtaskReviewSummaryReducer.recordedVerdicts(
  unitOfWork: UnitOfWork,
  output: Map<String, Any?>,
): List<ReviewFindingVerdict> = GoalSubtaskReviewStructuredFindingsParse.recordedVerdicts(unitOfWork, output)

internal fun GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(
  finding: StructuredGoalReviewFinding,
): List<String> = GoalSubtaskReviewStructuredFindingsParse.verificationBoundaryFindingPaths(finding)

internal fun GoalSubtaskReviewSummaryReducer.rejectedVerificationReasonTruncationRecord(findingId: String): String =
  GoalSubtaskReviewVerificationRejection.rejectedVerificationReasonTruncationRecord(findingId)

internal fun reviewPassVerdict(
  output: Map<String, Any?>,
  findings: List<GoalSubtaskReviewCompactFinding>,
  advanceBlockingCount: Int,
  hasOnlyNonBlockingFindings: Boolean,
): FeatureTaskRuntimeVerdict {
  val declaredVerdict = (output["verdict"] as? String)?.trim()
  val changesRequested = declaredVerdict in setOf("needs_fix", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
  val reportedFindingsWereFiltered = findings.isEmpty() &&
    GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output).isNotEmpty()
  return when {
    advanceBlockingCount > 0 -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    hasOnlyNonBlockingFindings || reportedFindingsWereFiltered -> FeatureTaskRuntimeVerdict.APPROVED
    changesRequested -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    declaredVerdict?.isNotBlank() == true -> FeatureTaskRuntimeVerdict.fromWire(declaredVerdict)
      .takeIf(GOAL_SUBTASK_REVIEW_PASS_VERDICTS::contains)
      ?: FeatureTaskRuntimeVerdict.APPROVED
    else -> FeatureTaskRuntimeVerdict.APPROVED
  }
}
