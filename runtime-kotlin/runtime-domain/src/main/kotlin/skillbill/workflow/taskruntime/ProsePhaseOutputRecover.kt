package skillbill.workflow.taskruntime

import skillbill.contracts.JsonCodec

internal object ProsePhaseOutputRecover {
  private val LEGACY_VALUE_KEYS: List<String> = listOf(
    "implementation_receipt",
    "executable_plan",
    "preplanning_digest",
    "gaps",
  )
  private val AUDIT_VERDICTS: Set<String> = setOf("satisfied", "gaps_found")
  private const val SUMMARY_MAX_CHARS: Int = 240
  private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237

  fun directValue(parsed: Map<String, Any?>): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed["produced_outputs"]) ?: return null
    return produced["value"]?.toString()?.takeIf { it.any { ch -> !ch.isWhitespace() } }
  }

  fun recoverLegacyValue(parsed: Map<String, Any?>): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed["produced_outputs"])
    if (produced != null) {
      for (key in LEGACY_VALUE_KEYS) {
        val stuffed = stuffSibling(produced[key])
        if (stuffed != null) return stuffed
      }
    }
    listValue(parsed["produced_outputs"])?.let { return it }
    return LEGACY_VALUE_KEYS.firstNotNullOfOrNull { key -> stuffSibling(parsed[key]) }
  }

  private fun listValue(producedOutputs: Any?): String? {
    val entries = (producedOutputs as? List<*>)?.takeIf { it.isNotEmpty() } ?: return null
    val singleValue = entries.singleOrNull()
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get("value")
      ?.toString()
      ?.takeIf { it.any { ch -> !ch.isWhitespace() } }
    return singleValue ?: stuffSibling(entries)
  }

  fun recoverPrompt(parsed: Map<String, Any?>?): String? {
    val produced = JsonCodec.anyToStringAnyMap(parsed?.get("produced_outputs"))
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
    val produced = JsonCodec.anyToStringAnyMap(parsed?.get("produced_outputs"))
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
  is Map<*, *> -> JsonCodec.anyToStringAnyMap(sibling)?.let(JsonCodec::mapToJsonString)
  is List<*> -> JsonCodec.mapToJsonString(linkedMapOf("entries" to sibling))
  else -> sibling.toString().takeIf { it.any { ch -> !ch.isWhitespace() } }
}
