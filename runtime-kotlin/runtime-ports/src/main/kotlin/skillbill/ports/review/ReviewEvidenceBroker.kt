package skillbill.ports.review

import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult

fun interface ReviewEvidenceBroker {
  fun read(request: ReviewEvidenceRequest): ReviewEvidenceResult
}
