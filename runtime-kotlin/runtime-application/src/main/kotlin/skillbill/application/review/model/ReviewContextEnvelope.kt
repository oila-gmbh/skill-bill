package skillbill.application.review.model

import skillbill.boundary.OpenBoundaryMap

class ReviewContextEnvelope(private val fields: Map<String, Any?>) {
  val kind: String get() = fields["kind"] as? String ?: ""

  @OpenBoundaryMap("Review-context wire map at the schema-validation seam")
  fun asWireMap(): Map<String, Any?> = fields

  override fun equals(other: Any?): Boolean = other is ReviewContextEnvelope && other.fields == fields

  override fun hashCode(): Int = fields.hashCode()

  override fun toString(): String = "ReviewContextEnvelope(kind=$kind)"
}
