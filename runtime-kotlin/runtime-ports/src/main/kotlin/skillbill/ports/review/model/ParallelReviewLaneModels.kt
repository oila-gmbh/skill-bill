package skillbill.ports.review.model

import skillbill.review.context.model.ProviderTokenUsage
import kotlin.time.Duration

data class ParallelReviewLaneRunRequest(
  val lane1: () -> ParallelReviewLaneOutcome,
  val lane2: () -> ParallelReviewLaneOutcome,
  val timeout: Duration,
)

data class ParallelReviewLaneRunResult(
  val lane1: ParallelReviewLaneOutcome,
  val lane2: ParallelReviewLaneOutcome,
)

data class ParallelReviewLaneOutcome(
  val success: Boolean,
  val rawOutput: String,
  val failureReason: String? = null,
  val tokenUsage: ProviderTokenUsage? = null,
)
