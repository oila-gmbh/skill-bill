package skillbill.application.review.model

import skillbill.boundary.OpenBoundaryMap

class ReviewContextEnvelope(fields: Map<String, Any?>) {
  private val fields: Map<String, Any?> = fields.toMap()

  val kind: String get() = fields["kind"] as? String ?: ""

  @OpenBoundaryMap("Review-context wire map at the schema-validation seam")
  fun asWireMap(): Map<String, Any?> = fields

  override fun equals(other: Any?): Boolean = other is ReviewContextEnvelope && other.fields == fields

  override fun hashCode(): Int = fields.hashCode()

  override fun toString(): String = "ReviewContextEnvelope(kind=$kind)"
}
