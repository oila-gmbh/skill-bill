package skillbill.db

import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection
import java.sql.ResultSet

// SKILL-66 Subtask 2: goal telemetry outbox emit. The generic lifecycle emit
// helpers are `session_id`-keyed, so goal rows (keyed by `workflow_id`, and the
// subtask events by the composite `(issue_key, subtask_id, workflow_id)`) get
// dedicated `*_event_emitted_at`-guarded variants. Each event is enqueued at
// most once: a resumed run that re-writes an already-emitted row is a no-op,
// satisfying the AC#4 no-double-count guarantee.

fun emitGoalStarted(connection: Connection, workflowId: String, level: String) {
  val row = goalRunSessionRow(connection, workflowId) ?: return
  if (row.stringOrEmpty("started_event_emitted_at").isNotBlank()) {
    return
  }
  enqueueTelemetry(connection, "skillbill_goal_started", goalStartedPayload(row, level))
  markGoalRunSessionEmitted(connection, "started_event_emitted_at", workflowId)
}

fun emitGoalFinished(connection: Connection, workflowId: String, level: String) {
  val row = goalRunSessionRow(connection, workflowId) ?: return
  if (row.stringOrEmpty("finished_event_emitted_at").isNotBlank()) {
    return
  }
  enqueueTelemetry(connection, "skillbill_goal_finished", goalFinishedPayload(row, level))
  markGoalRunSessionEmitted(connection, "finished_event_emitted_at", workflowId)
}

fun emitGoalSubtaskFinished(connection: Connection, record: GoalSubtaskFinishedRecord, level: String) {
  val row = goalSubtaskEventRow(connection, record.issueKey, record.subtaskId, record.workflowId) ?: return
  if (row.stringOrEmpty("subtask_event_emitted_at").isNotBlank()) {
    return
  }
  enqueueTelemetry(connection, "skillbill_goal_subtask_finished", goalSubtaskFinishedPayload(row, level))
  markGoalSubtaskEventEmitted(connection, record.issueKey, record.subtaskId, record.workflowId)
}

private fun goalRunSessionRow(connection: Connection, workflowId: String): Map<String, Any?>? =
  connection.prepareStatement("SELECT * FROM goal_run_sessions WHERE workflow_id = ?").use { statement ->
    statement.bind(workflowId)
    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toRowMap() else null }
  }

private fun goalSubtaskEventRow(
  connection: Connection,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
): Map<String, Any?>? = connection.prepareStatement(
  "SELECT * FROM goal_subtask_events WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?",
).use { statement ->
  statement.bind(issueKey, subtaskId, workflowId)
  statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toRowMap() else null }
}

private fun markGoalRunSessionEmitted(connection: Connection, columnName: String, workflowId: String) {
  connection.prepareStatement(
    "UPDATE goal_run_sessions SET $columnName = CURRENT_TIMESTAMP WHERE workflow_id = ?",
  ).use { statement ->
    statement.bind(workflowId)
    statement.executeUpdate()
  }
}

private fun markGoalSubtaskEventEmitted(connection: Connection, issueKey: String, subtaskId: Int, workflowId: String) {
  connection.prepareStatement(
    """
    UPDATE goal_subtask_events
    SET subtask_event_emitted_at = CURRENT_TIMESTAMP
    WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(issueKey, subtaskId, workflowId)
    statement.executeUpdate()
  }
}

private fun ResultSet.toRowMap(): Map<String, Any?> {
  val metadata = metaData
  return buildMap {
    for (index in 1..metadata.columnCount) {
      put(metadata.getColumnName(index), getObject(index))
    }
  }
}
