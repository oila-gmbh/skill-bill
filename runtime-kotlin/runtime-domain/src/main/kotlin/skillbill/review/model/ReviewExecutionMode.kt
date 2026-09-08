package skillbill.review.model

enum class ReviewExecutionMode(val wireValue: String) {
  INLINE("inline"),
  DELEGATED("delegated"),
  UNRESOLVED("unresolved");

  companion object {
    fun fromWire(value: String?): ReviewExecutionMode? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}
