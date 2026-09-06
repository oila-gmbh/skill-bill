package skillbill.workflow.taskruntime

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.model.PhaseOutputStatus

internal object ProsePhaseOutputParse {
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
    runCatching { PhaseOutputStatus.fromWire(lowercased).wireValue }.getOrNull()
}

private fun parseObject(raw: String): Map<String, Any?>? {
  if (raw.isBlank()) return null
  val obj = JsonCodec.parseObjectOrNull(raw) ?: return null
  return JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(obj))
}
