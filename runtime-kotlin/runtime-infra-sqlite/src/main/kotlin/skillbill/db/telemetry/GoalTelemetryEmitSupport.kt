package skillbill.db.telemetry

import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection

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

fun emitGoalIssueFinished(connection: Connection, parentWorkflowId: String, issueKey: String) {
  val row = goalIssueProgressRow(connection, parentWorkflowId, issueKey) ?: return
  if (row.stringOrEmpty("finished_event_emitted_at").isNotBlank()) {
    return
  }
  enqueueTelemetry(connection, "skillbill_goal_issue_finished", goalIssueFinishedPayload(row))
  markGoalIssueProgressEmitted(connection, parentWorkflowId, issueKey)
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

private fun goalIssueProgressRow(
  connection: Connection,
  parentWorkflowId: String,
  issueKey: String,
): Map<String, Any?>? = connection.prepareStatement(
  "SELECT * FROM goal_issue_progress WHERE parent_workflow_id = ? AND issue_key = ?",
).use { statement ->
  statement.bind(parentWorkflowId, issueKey)
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

private fun markGoalIssueProgressEmitted(connection: Connection, parentWorkflowId: String, issueKey: String) {
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET finished_event_emitted_at = CURRENT_TIMESTAMP
    WHERE parent_workflow_id = ? AND issue_key = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(parentWorkflowId, issueKey)
    statement.executeUpdate()
  }
}
