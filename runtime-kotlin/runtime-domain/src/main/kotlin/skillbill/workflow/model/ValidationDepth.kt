package skillbill.workflow.model

/**
 * How far the feature-task validate phase should go for a goal-continuation child.
 *
 * [BUILD_ONLY] is stamped on every non-final non-skipped subtask; [FULL] is stamped on the last
 * non-skipped subtask in manifest array order (including a single-subtask goal). Omitted wire /
 * legacy maps decode as [FULL] so non-goal and pre-field records keep today's full validate path.
 */
enum class ValidationDepth(val wireValue: String) {
  BUILD_ONLY("build_only"),
  FULL("full"),
  ;

  companion object {
    val DEFAULT: ValidationDepth = FULL

    fun fromWire(value: String): ValidationDepth = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException(
        "Unknown validation depth '$value'. Allowed: ${entries.joinToString { it.wireValue }}.",
      )
  }
}
