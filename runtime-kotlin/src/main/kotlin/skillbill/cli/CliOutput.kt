package skillbill.cli

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport
import skillbill.learnings.LearningScope

@OptIn(ExperimentalSerializationApi::class)
private val cliPrettyJson =
  Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
  }

internal object CliOutput {
  fun emit(payload: Map<String, Any?>, outputFormat: CliFormat): String = when (outputFormat) {
    CliFormat.JSON -> prettyJson(payload)
    CliFormat.TEXT -> emitText(payload)
  }

  fun numberedFindings(reviewRunId: String, numberedFindings: List<Map<String, Any?>>): String = buildString {
    appendLine("review_run_id: $reviewRunId")
    numberedFindings.forEach { finding ->
      appendLine(
        "${finding["number"]}. [${finding["finding_id"]}] " +
          "${finding["severity"]} | ${finding["confidence"]} | " +
          "${finding["location"]} | ${finding["description"]}",
      )
    }
  }

  fun triageResult(reviewRunId: String, decisions: List<Map<String, Any?>>): String = buildString {
    appendLine("review_run_id: $reviewRunId")
    decisions.forEach { decision ->
      append("${decision["number"]}. ${decision["finding_id"]} -> ${decision["outcome_type"]}")
      val note = decision["note"]?.toString().orEmpty()
      if (note.isNotEmpty()) {
        append(" | note: $note")
      }
      appendLine()
    }
  }

  fun learnings(entries: List<Map<String, Any?>>): String = if (entries.isEmpty()) {
    "No learnings found.\n"
  } else {
    buildString {
      entries.forEach { entry ->
        appendLine("${entry["reference"]}. [${entry["status"]}] ${scopedLabel(entry)} | ${entry["title"]}")
      }
    }
  }

  fun resolvedLearnings(
    repoScopeKey: String?,
    skillName: String?,
    scopePrecedence: List<LearningScope>,
    entries: List<Map<String, Any?>>,
  ): String = buildString {
    appendLine("scope_precedence: ${scopePrecedence.joinToString(" > ") { it.wireName }}")
    repoScopeKey?.let { appendLine("repo_scope_key: $it") }
    skillName?.let { appendLine("skill_name: $it") }
    appendLine("applied_learnings: ${summarizeAppliedLearnings(entries)}")
    if (entries.isEmpty()) {
      appendLine("No active learnings matched this review context.")
    } else {
      entries.forEach { entry ->
        appendLine(
          "- [${entry["reference"]}] ${scopedLabel(entry)} | ${entry["title"]} | ${entry["rule_text"]}",
        )
      }
    }
  }

  fun summarizeAppliedLearnings(entries: List<Map<String, Any?>>): String =
    if (entries.isEmpty()) "none" else entries.joinToString(", ") { it["reference"].toString() }
}

private fun emitText(payload: Map<String, Any?>): String = buildString {
  payload.forEach { (key, value) ->
    if (value == null) {
      return@forEach
    }
    when (value) {
      is Map<*, *>, is List<*> -> {
        appendLine("$key:")
        appendLine(prettyJsonValue(value))
      }
      else -> appendLine("$key: $value")
    }
  }
}

private fun prettyJson(payload: Map<String, Any?>): String =
  cliPrettyJson.encodeToString(JsonObject.serializer(), sortedJsonObject(payload))

private fun prettyJsonValue(value: Any?): String =
  cliPrettyJson.encodeToString(JsonElement.serializer(), sortedJsonElement(value))

private fun sortedJsonObject(payload: Map<String, Any?>): JsonObject =
  JsonObject(payload.toSortedMap().mapValues { (_, value) -> sortedJsonElement(value) })

private fun sortedJsonElement(value: Any?): JsonElement = when (value) {
  is Map<*, *> -> {
    val typedMap =
      value.entries
        .filter { it.key is String }
        .associate { it.key as String to it.value }
    sortedJsonObject(typedMap)
  }
  is Iterable<*> -> JsonArray(value.map(::sortedJsonElement))
  is Array<*> -> JsonArray(value.map(::sortedJsonElement))
  else -> JsonSupport.valueToJsonElement(value)
}

private fun scopedLabel(entry: Map<String, Any?>): String {
  val scope = entry["scope"].toString()
  val scopeKey = entry["scope_key"]?.toString().orEmpty()
  return if (scopeKey.isNotEmpty()) "$scope:$scopeKey" else scope
}
