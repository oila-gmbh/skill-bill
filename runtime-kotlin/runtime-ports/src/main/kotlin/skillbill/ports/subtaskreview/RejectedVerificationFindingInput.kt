package skillbill.ports.subtaskreview

internal data class RejectedVerificationFindingInput(
  val entry: Any?,
  val index: Int,
  val reviewRunId: String?,
  val reviewFindings: List<StructuredGoalReviewFinding>,
  val reviewById: Map<String, StructuredGoalReviewFinding>,
  val scope: UnaddressedFindingLedgerScope,
  val truncationRecords: MutableList<String>?,
)
