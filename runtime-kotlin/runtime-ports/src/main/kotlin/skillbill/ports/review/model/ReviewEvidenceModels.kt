package skillbill.ports.review.model

import skillbill.review.context.model.ReviewContextBudgetExceeded

data class ReviewEvidenceRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String? = null,
)

data class ReviewEvidenceResult(
  val content: String?,
  val bytes: Long,
  val cumulativeBytes: Long,
  val expansionCount: Int,
  val budgetExceeded: ReviewContextBudgetExceeded? = null,
)
