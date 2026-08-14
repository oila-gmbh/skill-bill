package skillbill.review.context

import skillbill.boundary.OpenBoundaryMap

fun interface ReviewContextEnvelopeValidator {
  @OpenBoundaryMap("Review-context wire map at the schema-validation seam")
  fun validate(envelope: Map<String, Any?>, sourceLabel: String)

  @OpenBoundaryMap("Spec-intent projection wire map at the schema-validation seam")
  fun validateSpecIntentProjection(payload: Map<String, Any?>, sourceLabel: String) = Unit
}
