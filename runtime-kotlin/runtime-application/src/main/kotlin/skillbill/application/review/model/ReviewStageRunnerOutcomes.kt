package skillbill.application.review.model

import skillbill.review.model.ReviewFindingVerdict

data class ReviewClaimVerificationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val skipReason: String? = null,
)

data class ReviewSpecAdjudicationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val skipReason: String? = null,
)
