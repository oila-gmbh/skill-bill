package skillbill.cli.kernel

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.application.review.model.ReviewSnapshotPruneResult
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonSupport

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

  fun numberedFindings(presentation: CliNumberedFindingsPresentation): String = buildString {
    appendLine("review_run_id: ${presentation.reviewRunId}")
    presentation.findings.forEach { finding ->
      appendLine(
        "${finding.number}. [${finding.findingId}] " +
          "${finding.severity} | ${finding.confidence} | " +
          "${finding.location} | ${finding.description}" +
          finding.claimVerdict?.let { " | claim_verdict=$it" }.orEmpty() +
          finding.scopeDisposition?.let { " | scope_disposition=$it" }.orEmpty() +
          finding.severityAdjustment?.let { " | severity_adjustment=$it" }.orEmpty(),
      )
    }
  }

  fun triageResult(presentation: CliTriagePresentation): String = buildString {
    appendLine("review_run_id: ${presentation.reviewRunId}")
    presentation.decisions.forEach { decision ->
      append("${decision.number}. ${decision.findingId} -> ${decision.outcomeType}")
      if (decision.note.isNotEmpty()) {
        append(" | note: ${decision.note}")
      }
      appendLine()
    }
  }

  fun reviewSnapshotPrune(result: ReviewSnapshotPruneResult): String = buildString {
    appendLine("live_db_path: ${result.liveDbPath} (never a prune candidate)")
    if (result.candidates.isEmpty()) {
      appendLine("No review-metrics snapshots found.")
      return@buildString
    }
    result.candidates.forEach { snapshot ->
      val disposition = when (snapshot) {
        in result.deleted -> "deleted"
        in result.failed -> "failed"
        else -> "kept"
      }
      appendLine("${snapshot.label} | ${snapshot.sizeBytes} bytes | ${snapshot.lastModified} | $disposition")
    }
    if (result.confirmed) {
      appendLine("Deleted ${result.deleted.size} snapshot(s), reclaiming ${result.reclaimedBytes} bytes.")
      if (result.failed.isNotEmpty()) {
        appendLine("Failed to delete ${result.failed.size} snapshot(s): ${result.failed.joinToString { it.label }}.")
      }
    } else {
      appendLine(
        "Dry run: ${result.candidates.size} snapshot(s) totalling ${result.candidateBytes} bytes. " +
          "Nothing was deleted. Re-run with --confirm to delete them.",
      )
    }
  }

  fun learnings(presentation: CliLearningListPresentation): String = if (presentation.entries.isEmpty()) {
    "No learnings found.\n"
  } else {
    buildString {
      presentation.entries.forEach { entry ->
        appendLine("${entry.reference}. [${entry.status}] ${entry.scopeLabel} | ${entry.title}")
      }
    }
  }

  fun resolvedLearnings(presentation: CliResolvedLearningsPresentation): String = buildString {
    appendLine("scope_precedence: ${presentation.scopePrecedence}")
    presentation.repoScopeKey?.let { appendLine("repo_scope_key: $it") }
    presentation.skillName?.let { appendLine("skill_name: $it") }
    appendLine("applied_learnings: ${presentation.appliedLearnings}")
    if (presentation.entries.isEmpty()) {
      appendLine("No active learnings matched this review context.")
    } else {
      presentation.entries.forEach { entry ->
        appendLine(
          "- [${entry.reference}] ${entry.scopeLabel} | ${entry.title} | ${entry.ruleText}",
        )
      }
    }
  }
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
