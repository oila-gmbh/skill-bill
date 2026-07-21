package skillbill.ports.review

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome

/**
 * Synchronous provider boundary for operations requested by a governed worker. Adapters must call
 * this protocol before performing an operation; a rejected response is returned to the worker and
 * the operation is not executed.
 */
interface NativeReviewOperationProtocol {
  fun read(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult
  fun tool(call: ReviewToolCall): ReviewToolCallResult

  /** Called synchronously before each provider model turn. A non-null result forbids the turn. */
  fun modelTurn(): ReviewBudgetOutcome?

  /** Called synchronously for an in-flight usage update. A non-null result forbids further work. */
  fun providerUsage(usage: ProviderTokenUsage): ReviewBudgetOutcome?
}

class BrokerBackedNativeReviewOperationProtocol(
  private val broker: ReviewEvidenceBroker,
) : NativeReviewOperationProtocol {
  override fun read(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult = broker.readBatch(request)
  override fun tool(call: ReviewToolCall): ReviewToolCallResult = broker.recordToolCall(call)
  override fun modelTurn(): ReviewBudgetOutcome? = broker.recordModelTurn()
  override fun providerUsage(usage: ProviderTokenUsage): ReviewBudgetOutcome? =
    broker.evaluateProviderUsage(usage, enforceable = true)
}
