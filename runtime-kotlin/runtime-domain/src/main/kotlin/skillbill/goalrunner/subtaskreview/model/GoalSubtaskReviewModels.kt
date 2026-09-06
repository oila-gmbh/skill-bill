package skillbill.goalrunner.subtaskreview.model

import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

data class StructuredGoalReviewFinding(
  val severity: String,
  val message: String,
  val issueCategory: String,
  val location: String,
  val compactLabel: String,
  val findingId: String? = null,
  val repositoryPath: String? = null,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
)

data class GoalSubtaskReviewOutputOutcome(
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
)

data class UnaddressedFindingLedgerScope(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val workflowId: WorkflowId,
  val reviewPassNumber: Int,
)
