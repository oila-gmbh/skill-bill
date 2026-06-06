package skillbill.infrastructure.sqlite.review

private val reviewHealthSources = listOf("standalone", "embedded", "malformed")
private val reviewHealthOutcomes =
  listOf("finding_accepted", "fix_applied", "finding_edited", "fix_rejected", "false_positive")

fun aggregateLatestOutcomeCounts(payloads: List<ReviewHealthPayload>): Map<String, Int> {
  val counts = reviewHealthOutcomes.associateWith { 0 }.toMutableMap()
  payloads.forEach { payload ->
    val latestOutcomeCounts = payload.payload["latest_outcome_counts"] as? Map<*, *>
    if (latestOutcomeCounts == null) {
      reviewFindingDetails(payload.payload).forEach { detail ->
        addOutcomeCount(counts, detail["outcome_type"]?.toString().orEmpty(), 1)
      }
    } else {
      latestOutcomeCounts.forEach { (key, value) -> addOutcomeCount(counts, key?.toString().orEmpty(), value.asInt()) }
    }
  }
  return counts.toMap()
}

fun aggregateFindingDetailCounts(
  payloads: List<ReviewHealthPayload>,
  fieldName: String,
  expectedValues: List<String>,
): Map<String, Int> {
  val counts = expectedValues.associateWith { 0 }.toMutableMap()
  payloads.forEach { payload ->
    reviewFindingDetails(payload.payload).forEach { detail ->
      val value = normalizeFindingDetailValue(fieldName, detail[fieldName]?.toString().orEmpty())
      if (value.isNotBlank()) {
        counts[value] = counts.getOrDefault(value, 0) + 1
      }
    }
  }
  return counts.toMap()
}

fun aggregatePayloadValueCounts(
  payloads: List<ReviewHealthPayload>,
  fieldName: String,
  expectedValues: List<String>,
  defaultValue: String,
): Map<String, Int> {
  val counts = expectedValues.associateWith { 0 }.toMutableMap()
  payloads.forEach { payload ->
    val value = payload.payload.stringHealthValue(fieldName).ifBlank { defaultValue }
    counts[value] = counts.getOrDefault(value, 0) + 1
  }
  return counts.toMap()
}

fun countReviewHealthSources(payloads: List<ReviewHealthPayload>, malformedRecords: Int): Map<String, Int> {
  val counts = reviewHealthSources.associateWith { 0 }.toMutableMap()
  payloads.forEach { payload -> counts[payload.source] = counts.getValue(payload.source) + 1 }
  counts["malformed"] = malformedRecords
  return counts.toMap()
}

private fun addOutcomeCount(counts: MutableMap<String, Int>, key: String, count: Int) {
  if (key in counts) {
    counts[key] = counts.getValue(key) + count
  }
}

private fun reviewFindingDetails(payload: Map<String, Any?>): List<Map<*, *>> =
  (payload["accepted_finding_details"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>() +
    (payload["rejected_finding_details"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

private fun normalizeFindingDetailValue(fieldName: String, value: String): String = when (fieldName) {
  "confidence" -> when (value.lowercase()) {
    "high" -> "High"
    "medium" -> "Medium"
    "low" -> "Low"
    else -> value
  }
  else -> value
}

private fun Any?.asInt(): Int = when (this) {
  is Number -> toInt()
  is String -> toIntOrNull() ?: 0
  else -> 0
}
