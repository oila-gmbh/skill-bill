package skillbill.ports.review

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome

/**
 * The single measured surface a delegated specialist may act through. Every call is policy-checked
 * and accounted; once a lane produces a terminal outcome the broker keeps returning that outcome
 * rather than serving more context.
 */
interface ReviewEvidenceBroker {
  fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult

  fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult

  fun recordModelTurn(): ReviewBudgetOutcome?

  fun validateLaneResult(result: String): ReviewBudgetOutcome?

  /** Observes cumulative provider result bytes while the lane is still running. */
  fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome?

  fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome?

  fun accounting(): ReviewLaneAccounting

  fun terminalOutcome(): ReviewBudgetOutcome?
}

fun interface ReviewEvidenceBrokerFactory {
  fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker
}
