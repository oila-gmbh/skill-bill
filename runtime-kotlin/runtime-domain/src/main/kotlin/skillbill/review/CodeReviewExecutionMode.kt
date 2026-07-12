package skillbill.review

/** The caller's immutable execution-policy request for a routed code review. */
enum class CodeReviewExecutionMode(val wireValue: String) {
  AUTO("auto"),
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    fun fromWire(value: String): CodeReviewExecutionMode =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException(
          "Unknown code-review execution mode '$value'. Allowed: ${entries.joinToString { it.wireValue }}.",
        )
  }
}
