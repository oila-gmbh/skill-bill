package skillbill.goalrunner.subtaskreview

import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.review.model.ReviewRunId

internal data class RejectedVerificationFindingInput(
  val entry: Any?,
  val index: Int,
  val reviewRunId: ReviewRunId?,
  val reviewFindings: List<StructuredGoalReviewFinding>,
  val reviewById: Map<String, StructuredGoalReviewFinding>,
  val scope: UnaddressedFindingLedgerScope,
  val truncationRecords: MutableList<String>?,
)
