package skillbill.review

import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary

data class ReviewStageDegradationSelectionRequest(
  val reviewRunId: String,
  val spec: ReviewSpecProjectionReference?,
  val boundaries: List<ReviewStageBoundary>,
  val verdicts: List<ReviewFindingVerdict>,
  val claims: ReviewPassClaimSnapshot?,
  val evidenceBoundaries: List<ReviewEvidenceBoundaryAccounting> = emptyList(),
)
