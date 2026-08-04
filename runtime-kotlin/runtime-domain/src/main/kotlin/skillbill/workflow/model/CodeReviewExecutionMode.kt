package skillbill.workflow.model

/**
 * The caller's immutable execution-policy request for a routed code review.
 *
 * [DELEGATED] is the specialist subagent fan-out; [INLINE] is the single-prompt review. [INLINE] is
 * the default, and [DELEGATED] is the experimental tier reached only by an explicit caller token —
 * neither an omitted selection nor [AUTO] resolves to it, because a fan-out re-pays the whole
 * per-turn context floor once per lane. [fromWire] never reinterprets a token carrying
 * pre-SKILL-159 semantics: a persisted record at an older contract version loud-fails at its own
 * schema seam instead.
 */
enum class CodeReviewExecutionMode(val wireValue: String) {
  AUTO("auto"),
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    val DEFAULT: CodeReviewExecutionMode = INLINE

    fun fromWire(value: String): CodeReviewExecutionMode = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException(
        "Unknown code-review execution mode '$value'. Allowed: ${entries.joinToString { it.wireValue }}.",
      )
  }
}
