package skillbill.review.model

data class ReviewStageDegradationSelectionRequest(
  val reviewRunId: String,
  val spec: ReviewSpecProjectionReference?,
  val boundaries: List<ReviewStageBoundary>,
  val verdicts: List<ReviewFindingVerdict>,
  val claims: ReviewPassClaimSnapshot?,
  val evidenceBoundaries: List<ReviewEvidenceBoundaryAccounting> = emptyList(),
)
