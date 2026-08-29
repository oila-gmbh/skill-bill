package skillbill.workflow.taskruntime.model

import java.security.MessageDigest

/** Closed response-availability states. A non-exact body is never labeled exact. */
enum class CorrectiveRepairResponseAvailability(val wireValue: String) {
  EXACT_RESPONSE_INCLUDED("exact_response_included"),
  RESPONSE_ALREADY_TRUNCATED("response_already_truncated"),
  RESPONSE_EXCEEDS_REPAIR_BUDGET("response_exceeds_repair_budget"),
  RESPONSE_UNAVAILABLE("response_unavailable"),
  ;

  companion object {
    fun fromWire(raw: String): CorrectiveRepairResponseAvailability = entries.firstOrNull { it.wireValue == raw }
      ?: throw IllegalArgumentException(
        "CorrectiveRepairResponseAvailability '$raw' is not a declared availability state.",
      )
  }
}

/** Explicit inclusion or truncation reason paired with [CorrectiveRepairResponseAvailability]. */
enum class CorrectiveRepairInclusionReason(val wireValue: String) {
  EXACT_WITHIN_BUDGET("exact_within_budget"),
  CAPTURE_ALREADY_TRUNCATED("capture_already_truncated"),
  CAPTURE_EXCEEDS_RESPONSE_BUDGET("capture_exceeds_response_budget"),
  CAPTURE_UNAVAILABLE("capture_unavailable"),
  PROMPT_FRAMING_EXCEEDS_BUDGET("prompt_framing_exceeds_budget"),
  ;

  companion object {
    fun fromWire(raw: String): CorrectiveRepairInclusionReason = entries.firstOrNull { it.wireValue == raw }
      ?: throw IllegalArgumentException(
        "CorrectiveRepairInclusionReason '$raw' is not a declared inclusion reason.",
      )
  }
}
