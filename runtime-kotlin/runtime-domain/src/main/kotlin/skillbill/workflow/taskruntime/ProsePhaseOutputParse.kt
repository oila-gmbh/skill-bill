package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport

internal object ProsePhaseOutputParse {
  private val STATUS_TOKENS: Set<String> = setOf("completed", "blocked", "failed")
  private val STATUS_ALIASES: Map<String, String> = mapOf(
    "complete" to "completed",
    "block" to "blocked",
    "fail" to "failed",
  )
  private val FENCED_JSON: Regex =
    Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

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
    return parsedStatus == null || canonicalStatus(parsedStatus) != null
  }

  fun recoverStatus(parsed: Map<String, Any?>): String? {
    val raw = parsed["status"]?.toString()?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return "completed"
    return canonicalStatus(raw)
  }

  private fun canonicalStatus(lowercased: String): String? =
    lowercased.takeIf { it in STATUS_TOKENS } ?: STATUS_ALIASES[lowercased]
}

private fun parseObject(raw: String): Map<String, Any?>? {
  if (raw.isBlank()) return null
  val obj = JsonSupport.parseObjectOrNull(raw) ?: return null
  return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(obj))
}
