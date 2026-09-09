package skillbill.ports.review

import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ReviewBudgetOutcome

interface ReviewEvidenceLaneAccounting {
  fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult

  fun recordModelTurn(): ReviewBudgetOutcome?

  fun validateLaneResult(result: String): ReviewBudgetOutcome?

  fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome?

  fun hasObservedLaneResult(): Boolean = accounting().resultBytes > 0

  fun accounting(): ReviewLaneAccounting

  fun terminalOutcome(): ReviewBudgetOutcome?
}
