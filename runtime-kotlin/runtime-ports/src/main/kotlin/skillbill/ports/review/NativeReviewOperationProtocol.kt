package skillbill.ports.review

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceDiscoveryPage
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

interface NativeReviewOperationProtocol {
  fun discover(request: ReviewEvidenceDiscoveryRequest): ReviewEvidenceDiscoveryPage =
    error("This broker does not support evidence discovery.")
  fun expansionById(id: String): ReviewExpansionRecord? = null
  fun confirmDelivery(receipt: String) = Unit
  fun recordMalformedRequest() = Unit
  fun finishDeliverySession() = Unit

  fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord
  fun read(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult
  fun tool(call: ReviewToolCall): ReviewToolCallResult

  fun modelTurn(): ReviewBudgetOutcome?

  fun laneResultChunk(chunk: String): ReviewBudgetOutcome?
}

class BrokerBackedNativeReviewOperationProtocol(
  private val broker: ReviewEvidenceBroker,
) : NativeReviewOperationProtocol {
  override fun discover(request: ReviewEvidenceDiscoveryRequest): ReviewEvidenceDiscoveryPage = broker.discover(request)
  override fun expansionById(id: String): ReviewExpansionRecord? = broker.expansionById(id)
  override fun confirmDelivery(receipt: String) = broker.confirmDelivery(receipt)
  override fun recordMalformedRequest() = broker.recordMalformedRequest()
  override fun finishDeliverySession() = broker.finishDeliverySession()
  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    broker.authorizeExpansion(request)
  override fun read(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult = broker.readBatch(request)
  override fun tool(call: ReviewToolCall): ReviewToolCallResult = broker.recordToolCall(call)
  override fun modelTurn(): ReviewBudgetOutcome? = broker.recordModelTurn()
  override fun laneResultChunk(chunk: String): ReviewBudgetOutcome? = broker.observeLaneResultChunk(chunk)
}
