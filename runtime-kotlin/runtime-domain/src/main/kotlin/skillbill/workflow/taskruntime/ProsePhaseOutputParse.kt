package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport

internal object ProsePhaseOutputParse {
  private val LEGACY_VALUE_KEYS: List<String> = listOf(
    "implementation_receipt",
    "executable_plan",
    "preplanning_digest",
    "gaps",
  )

  private val STATUS_TOKENS: Set<String> = setOf("completed", "blocked", "failed")
  private val AUDIT_VERDICTS: Set<String> = setOf("satisfied", "gaps_found")
  private val FENCED_JSON: Regex =
    Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
  private const val SUMMARY_MAX_CHARS: Int = 240
  private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237

  fun bestEffortParse(text: String): Map<String, Any?>? {
    parseObject(text.trim())?.let { return it }
    for (match in FENCED_JSON.findAll(text)) {
      parseObject(match.groupValues[1].trim())?.let { return it }
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start >= 0 && end > start) {
      parseObject(text.substring(start, end + 1))?.let { return it }
    }
    return null
  }

  fun identityCompatible(parsed: Map<String, Any?>, phaseId: String): Boolean {
    val parsedPhase = parsed["phase_id"]?.toString()
    if (parsedPhase != null && parsedPhase != phaseId) return false
    val parsedStatus = parsed["status"]?.toString()?.trim()?.lowercase()
    return parsedStatus == null || parsedStatus in STATUS_TOKENS
  }

  fun recoverStatus(parsed: Map<String, Any?>): String? {
    val raw = parsed["status"]?.toString()?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return "completed"
    return raw.takeIf { it in STATUS_TOKENS }
  }

  fun directValue(parsed: Map<String, Any?>): String? {
    val produced = JsonSupport.anyToStringAnyMap(parsed["produced_outputs"]) ?: return null
    return produced["value"]?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
  }

  fun recoverLegacyValue(parsed: Map<String, Any?>): String? {
    val produced = JsonSupport.anyToStringAnyMap(parsed["produced_outputs"])
    if (produced != null) {
      for (key in LEGACY_VALUE_KEYS) {
        val stuffed = stuffSibling(produced[key])
        if (stuffed != null) return stuffed
      }
    }
    return LEGACY_VALUE_KEYS.firstNotNullOfOrNull { key -> stuffSibling(parsed[key]) }
  }

  fun recoverPrompt(parsed: Map<String, Any?>?): String? {
    val produced = JsonSupport.anyToStringAnyMap(parsed?.get("produced_outputs"))
    return produced?.get("prompt")?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
      ?: parsed?.get("prompt")?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
  }

  fun recoverSummary(parsed: Map<String, Any?>?, value: String): String {
    val fromField = parsed?.get("summary")?.toString()?.trim()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
    if (fromField != null) return fromField
    val compact = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      compact.length <= SUMMARY_MAX_CHARS -> compact.ifBlank { "Prose phase completed." }
      else -> compact.take(SUMMARY_ELLIPSIS_PREFIX) + "..."
    }
  }

  fun recoverAuditVerdict(parsed: Map<String, Any?>?, rawText: String): String? {
    val fromField = parsed?.get("verdict")?.toString()?.trim()?.lowercase()
    if (fromField in AUDIT_VERDICTS) return fromField
    val produced = JsonSupport.anyToStringAnyMap(parsed?.get("produced_outputs"))
    val fromProduced = produced?.get("verdict")?.toString()?.trim()?.lowercase()
    if (fromProduced in AUDIT_VERDICTS) return fromProduced
    val lower = rawText.lowercase()
    val hasSatisfied = Regex("""\bsatisfied\b""").containsMatchIn(lower)
    val hasGaps = Regex("""\bgaps_found\b""").containsMatchIn(lower)
    return when {
      hasSatisfied && !hasGaps -> "satisfied"
      hasGaps && !hasSatisfied -> "gaps_found"
      else -> null
    }
  }

  fun recoverFailureDisposition(parsed: Map<String, Any?>?): String? =
    parsed?.get("failure_disposition")?.toString()?.trim()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
}

private fun stuffSibling(sibling: Any?): String? = when (sibling) {
  null -> null
  is String -> sibling.takeIf { it.any { ch -> !ch.isWhitespace() } }
  is Map<*, *> -> JsonSupport.anyToStringAnyMap(sibling)?.let(JsonSupport::mapToJsonString)
  is List<*> -> JsonSupport.mapToJsonString(linkedMapOf("entries" to sibling))
  else -> sibling.toString().takeIf { it.any { ch -> !ch.isWhitespace() } }
}

private fun parseObject(raw: String): Map<String, Any?>? {
  if (raw.isBlank()) return null
  val obj = JsonSupport.parseObjectOrNull(raw) ?: return null
  @Suppress("UNCHECKED_CAST")
  return JsonSupport.jsonElementToValue(obj) as? Map<String, Any?>
}
