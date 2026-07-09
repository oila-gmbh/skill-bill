package skillbill.application.telemetry

private val blockedReasonPrefixes = setOf(
  "limit",
  "validation",
  "fix_loop",
  "git",
  "store_lock",
  "needs_human",
  "schema",
  "runtime",
  "unknown",
)

fun normalizedBlockedReason(
  reason: String?,
  category: String,
  fallback: String,
): String {
  val normalizedCategory = category.takeIf { it in blockedReasonPrefixes } ?: "unknown"
  val trimmedReason = reason?.trim().orEmpty()
  val raw = trimmedReason.ifBlank { fallback.trim().ifBlank { "Blocked without a specific reason." } }
  return if (raw.substringBefore(":", missingDelimiterValue = "") in blockedReasonPrefixes) {
    raw
  } else {
    "$normalizedCategory: $raw"
  }
}
