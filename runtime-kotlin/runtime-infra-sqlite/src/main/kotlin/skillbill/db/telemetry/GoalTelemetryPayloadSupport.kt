package skillbill.db.telemetry

import skillbill.contracts.JsonSupport

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
  "duration_ms" to row.longOrZero("finished_duration_ms"),
  "subtasks_complete" to row.intOrZero("subtasks_complete"),
  "subtasks_blocked" to row.intOrZero("subtasks_blocked"),
  "subtasks_skipped" to row.intOrZero("subtasks_skipped"),
).apply {
  if (level == "full") {
    put("feature_name", row.stringOrEmpty("feature_name"))
  }
}

fun goalSubtaskFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> = linkedMapOf<String, Any?>(
  "workflow_id" to row.stringOrEmpty("workflow_id"),
  "issue_key" to row.stringOrEmpty("issue_key"),
  "subtask_id" to row.intOrZero("subtask_id"),
  "status" to row.stringOrEmpty("status"),
  "started_at" to row.stringOrEmpty("started_at"),
  "finished_at" to row.stringOrEmpty("finished_at"),
  "duration_ms" to row.longOrZero("duration_ms"),
  "attempt_count" to row.intOrZero("attempt_count"),
).apply {
  if (level == "full") {
    put("subtask_name", row.stringOrEmpty("subtask_name"))
    put("blocked_reason", row["blocked_reason"]?.toString())
    put("finalizing_agent_id", row["finalizing_agent_id"]?.toString()?.takeIf(String::isNotBlank))
    put(
      "participating_agent_ids",
      parseAgentIdArray(row.stringOrEmpty("participating_agent_ids"), row.stringOrEmpty("workflow_id")),
    )
    put("boundary_history_written", row.booleanFromInt("boundary_history_written"))
    put("boundary_history_value", row.stringOrEmpty("boundary_history_value").ifBlank { "none" })
  }
}
