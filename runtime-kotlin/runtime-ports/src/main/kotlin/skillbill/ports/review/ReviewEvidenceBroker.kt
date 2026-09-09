package skillbill.ports.review

import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceDiscoveryPage
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.ReviewExpansionRecord

interface ReviewEvidenceBroker : ReviewEvidenceLaneAccounting {
  fun discover(request: ReviewEvidenceDiscoveryRequest): ReviewEvidenceDiscoveryPage =
    error("This broker does not support evidence discovery.")
  fun expansionById(id: String): ReviewExpansionRecord? = null
  fun confirmDelivery(receipt: String) = Unit
  fun recordMalformedRequest() = Unit
  fun finishDeliverySession() = Unit

  fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    error("This evidence broker does not support governed complete-file expansion.")

  fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult
}

fun interface ReviewEvidenceBrokerFactory {
  fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker
}
