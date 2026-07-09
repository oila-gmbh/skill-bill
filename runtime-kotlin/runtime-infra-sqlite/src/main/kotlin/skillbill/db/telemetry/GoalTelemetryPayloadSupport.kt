package skillbill.db.telemetry

import skillbill.contracts.JsonSupport
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val MILLIS_PER_SECOND = 1000L

private fun parseAgentIdArray(rawValue: String, workflowId: String): List<Any?> {
  if (rawValue.isBlank()) return emptyList()
  val trimmed = rawValue.trim()
  if (!trimmed.startsWith("[")) {
    System.err.println(
      "skillbill telemetry: malformed participating_agent_ids for workflow $workflowId; " +
        "expected a JSON array, got: $rawValue",
    )
    return emptyList()
  }
  return JsonSupport.parseArrayOrEmpty(trimmed).also { result ->
    if (result.isEmpty() && trimmed != "[]") {
      System.err.println(
        "skillbill telemetry: failed to parse participating_agent_ids for workflow $workflowId; " +
          "value: $rawValue",
      )
    }
  }
}

fun goalStartedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> = linkedMapOf<String, Any?>(
  "workflow_id" to row.stringOrEmpty("workflow_id"),
  "issue_key" to row.stringOrEmpty("issue_key"),
  "subtask_total" to row.intOrZero("subtask_total"),
  "resumed" to row.booleanFromInt("resumed"),
  "started_at" to row.stringOrEmpty("started_at"),
  "status" to "running",
  "mode" to row.stringOrEmpty("mode").ifBlank { "runtime" },
).apply {
  if (level == "full") {
    put("feature_name", row.stringOrEmpty("feature_name"))
  }
}

fun goalFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> = linkedMapOf<String, Any?>(
  "workflow_id" to row.stringOrEmpty("workflow_id"),
  "issue_key" to row.stringOrEmpty("issue_key"),
  "status" to row.stringOrEmpty("status"),
  "started_at" to row.stringOrEmpty("started_at"),
  "finished_at" to row.stringOrEmpty("finished_at"),
  "duration_seconds" to secondsFromMillis(row.longOrZero("finished_duration_ms")),
  "subtasks_complete" to row.intOrZero("subtasks_complete"),
  "subtasks_blocked" to row.intOrZero("subtasks_blocked"),
  "subtasks_skipped" to row.intOrZero("subtasks_skipped"),
  "mode" to row.stringOrEmpty("mode").ifBlank { "runtime" },
  "stop_reason" to row["stop_reason"]?.toString(),
).apply {
  if (level == "full") {
    put("feature_name", row.stringOrEmpty("feature_name"))
  }
}

fun goalIssueFinishedPayload(row: Map<String, Any?>): Map<String, Any?> {
  val firstStartedAt = row.stringOrEmpty("first_started_at")
  val finishedAt = row.stringOrEmpty("finished_at")
  return linkedMapOf<String, Any?>(
    "parent_workflow_id" to row.stringOrEmpty("parent_workflow_id"),
    "issue_key" to row.stringOrEmpty("issue_key"),
    "status" to row.stringOrEmpty("status"),
    "subtasks_complete" to row.intOrZero("subtasks_complete"),
    "subtasks_blocked" to row.intOrZero("subtasks_blocked"),
    "subtasks_skipped" to row.intOrZero("subtasks_skipped"),
    "total_invocations" to row.intOrZero("total_invocations"),
    "total_blocks" to row.intOrZero("total_blocks"),
    "total_resumes" to row.intOrZero("total_resumes"),
    "first_started_at" to firstStartedAt,
    "finished_at" to finishedAt,
    "duration_seconds" to durationBetweenSeconds(firstStartedAt, finishedAt),
    "mode" to row.stringOrEmpty("mode"),
  )
}

fun goalSubtaskFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> = linkedMapOf<String, Any?>(
  "workflow_id" to row.stringOrEmpty("workflow_id"),
  "issue_key" to row.stringOrEmpty("issue_key"),
  "subtask_id" to row.intOrZero("subtask_id"),
  "status" to row.stringOrEmpty("status"),
  "started_at" to row.stringOrEmpty("started_at"),
  "finished_at" to row.stringOrEmpty("finished_at"),
  "duration_seconds" to secondsFromMillis(row.longOrZero("duration_ms")),
  "attempt_count" to row.intOrZero("attempt_count"),
  "blocked_reason" to row["blocked_reason"]?.toString(),
).apply {
  if (level == "full") {
    put("subtask_name", row.stringOrEmpty("subtask_name"))
    put("finalizing_agent_id", row["finalizing_agent_id"]?.toString()?.takeIf(String::isNotBlank))
    put(
      "participating_agent_ids",
      parseAgentIdArray(row.stringOrEmpty("participating_agent_ids"), row.stringOrEmpty("workflow_id")),
    )
    put("boundary_history_written", row.booleanFromInt("boundary_history_written"))
    put("boundary_history_value", row.stringOrEmpty("boundary_history_value").ifBlank { "none" })
  }
}

private fun secondsFromMillis(durationMs: Long): Long = durationMs.coerceAtLeast(0) / MILLIS_PER_SECOND

private fun durationBetweenSeconds(startedAt: String, finishedAt: String): Long = runCatching {
  java.time.Duration.between(parseTelemetryTimestamp(startedAt), parseTelemetryTimestamp(finishedAt))
    .seconds
    .coerceAtLeast(0)
}.getOrDefault(0)

private fun parseTelemetryTimestamp(value: String): Instant = runCatching {
  Instant.parse(value)
}.getOrElse {
  LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
}
