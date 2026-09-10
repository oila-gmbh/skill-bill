package skillbill.infrastructure.fs
import skillbill.ports.review.ReviewEvidenceLaneAccounting
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ReviewBudgetOutcome

internal class FileSystemReviewEvidenceLaneAccounting(
  private val context: FileSystemReviewEvidenceBrokerContext,
) : ReviewEvidenceLaneAccounting {
  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult = synchronized(context) {
    context.recordToolCall(call)
  }
  override fun recordModelTurn(): ReviewBudgetOutcome? = synchronized(context) { context.recordModelTurn() }
  override fun validateLaneResult(result: String): ReviewBudgetOutcome? = synchronized(context) {
    context.validateLaneResult(result)
  }
  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? = synchronized(context) {
    context.observeLaneResultChunk(chunk)
  }
  override fun hasObservedLaneResult(): Boolean = synchronized(context) { context.hasObservedLaneResult() }
  override fun accounting(): ReviewLaneAccounting = synchronized(context) { context.accounting() }
  override fun terminalOutcome(): ReviewBudgetOutcome? = synchronized(context) { context.terminalOutcome() }
}
