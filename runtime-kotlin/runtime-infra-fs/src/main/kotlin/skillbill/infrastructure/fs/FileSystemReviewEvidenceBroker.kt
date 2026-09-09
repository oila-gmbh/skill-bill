package skillbill.infrastructure.fs
import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewEvidenceLaneAccounting
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceDiscoveryPage
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.ReviewExpansionRecord

@Inject
class FileSystemReviewEvidenceBrokerFactory : ReviewEvidenceBrokerFactory {
  override fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker =
    FileSystemReviewEvidenceBroker(binding)
}
class FileSystemReviewEvidenceBroker private constructor(
  private val context: FileSystemReviewEvidenceBrokerContext,
  private val metrics: FileSystemReviewEvidenceLaneAccounting,
) : ReviewEvidenceBroker, ReviewEvidenceLaneAccounting by metrics {
  private constructor(context: FileSystemReviewEvidenceBrokerContext) :
    this(context, FileSystemReviewEvidenceLaneAccounting(context))

  constructor(binding: ReviewEvidenceBrokerBinding) : this(FileSystemReviewEvidenceBrokerContext(binding))

  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    synchronized(context) { context.measuredRequest { context.authorizeMeasuredExpansion(request) } }

  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult =
    synchronized(context) { context.measuredRequest { context.readMeasuredBatch(request) } }

  override fun discover(request: ReviewEvidenceDiscoveryRequest): ReviewEvidenceDiscoveryPage = synchronized(context) {
    context.measuredRequest {
      require(context.readState.terminalOutcome == null) { "Evidence request budget is exhausted." }
      context.catalog.discover(request)
    }
  }

  override fun recordMalformedRequest() = synchronized(context) {
    context.countRequest()
    context.recordRequestRefusal()
  }

  override fun expansionById(id: String): ReviewExpansionRecord? = synchronized(context) {
    context.authorizedExpansionLedger.singleOrNull { it.expansionId == id }
  }

  override fun confirmDelivery(receipt: String) = synchronized(context) { context.catalog.confirm(receipt) }

  override fun finishDeliverySession() = synchronized(context) {
    if (context.catalog.unconfirmedCount() > 0) {
      context.refusedOperationCount += 1
      appendReviewRefusal(
        context.refusalLedger,
        ReviewRefusedOperationRecord(
          "unconfirmed_delivery",
          "review-evidence",
        ),
      )
    }
  }
}
