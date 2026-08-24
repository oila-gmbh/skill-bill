package skillbill.application.review.model

import skillbill.agent.model.AgentPhaseOutput
import skillbill.review.model.ReviewFindingVerdict

data class ReviewClaimVerificationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val output: AgentPhaseOutput? = null,
  val skipReason: String? = null,
)

data class ReviewSpecAdjudicationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val skipReason: String? = null,
)
