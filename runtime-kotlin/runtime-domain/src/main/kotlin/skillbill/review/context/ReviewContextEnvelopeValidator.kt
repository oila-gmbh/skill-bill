package skillbill.review.context

import skillbill.boundary.OpenBoundaryMap

interface ReviewContextEnvelopeValidator {
  @OpenBoundaryMap("Review-context wire map at the schema-validation seam")
  fun validate(envelope: Map<String, Any?>, sourceLabel: String)
}
