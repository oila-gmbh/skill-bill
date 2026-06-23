package skillbill.db.telemetry

import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection
import java.sql.ResultSet

fun saveGoalStarted(connection: Connection, record: GoalStartedRecord) {
  if (goalRunSessionExists(connection, record.workflowId)) {
    updateGoalStarted(connection, record)
    return
  }
  connection.prepareStatement(
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, feature_name, subtask_total, resumed, started_at
    ) VALUES (?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.workflowId,
      record.issueKey,
      record.featureName,
      record.subtaskTotal,
      record.resumed.toSqlInt(),
      record.startedAt,
    )
    statement.executeUpdate()
  }
}

private fun updateGoalStarted(connection: Connection, record: GoalStartedRecord) {
  connection.prepareStatement(
    """
    UPDATE goal_run_sessions SET
      issue_key = ?,
      feature_name = ?,
      subtask_total = ?,
      resumed = ?,
      started_at = ?
    WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.issueKey,
      record.featureName,
      record.subtaskTotal,
      record.resumed.toSqlInt(),
      record.startedAt,
      record.workflowId,
    )
    statement.executeUpdate()
  }
}

fun saveGoalFinished(connection: Connection, record: GoalFinishedRecord) {
  if (goalRunSessionExists(connection, record.workflowId)) {
    updateGoalFinished(connection, record)
  } else {
    insertGoalFinished(connection, record)
  }
}

private fun updateGoalFinished(connection: Connection, record: GoalFinishedRecord) {
  connection.prepareStatement(
    """
    UPDATE goal_run_sessions SET
      issue_key = ?,
      status = ?,
      started_at = ?,
      finished_at = ?,
      finished_duration_ms = ?,
      subtasks_complete = ?,
      subtasks_blocked = ?,
      subtasks_skipped = ?
    WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.issueKey,
      record.status,
      record.startedAt,
      record.finishedAt,
      record.durationMs,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.workflowId,
    )
    statement.executeUpdate()
  }
}

private fun insertGoalFinished(connection: Connection, record: GoalFinishedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, started_at, status, finished_at,
      finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.workflowId,
      record.issueKey,
      record.startedAt,
      record.status,
      record.finishedAt,
      record.durationMs,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
    )
    statement.executeUpdate()
  }
}

fun saveGoalSubtaskFinished(connection: Connection, record: GoalSubtaskFinishedRecord): Boolean {
  val inserted = connection.prepareStatement(
    """
    INSERT INTO goal_subtask_events (
      issue_key, workflow_id, subtask_id, subtask_name, status,
      started_at, finished_at, duration_ms, attempt_count, blocked_reason,
      finalizing_agent_id, participating_agent_ids
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (issue_key, subtask_id, workflow_id) DO NOTHING
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.issueKey,
      record.workflowId,
      record.subtaskId,
      record.subtaskName,
      record.status,
      record.startedAt,
      record.finishedAt,
      record.durationMs,
      record.attemptCount,
      record.blockedReason,
      record.finalizingAgentId,
      listJson(record.participatingAgentIds),
    )
    statement.executeUpdate() > 0
  }
  connection.prepareStatement(
    """
    UPDATE goal_subtask_events
    SET
      boundary_history_value = COALESCE(
        (SELECT fis.boundary_history_value
         FROM feature_task_workflows ftw
         JOIN feature_implement_sessions fis ON fis.session_id = ftw.session_id
         WHERE ftw.workflow_id = ?),
        'none'
      ),
      boundary_history_written = COALESCE(
        (SELECT fis.boundary_history_written
         FROM feature_task_workflows ftw
         JOIN feature_implement_sessions fis ON fis.session_id = ftw.session_id
         WHERE ftw.workflow_id = ?),
        0
      )
    WHERE issue_key = ? AND subtask_id = ? AND workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.workflowId,
      record.workflowId,
      record.issueKey,
      record.subtaskId,
      record.workflowId,
    )
    statement.executeUpdate()
  }
  return inserted
}

private fun goalRunSessionExists(connection: Connection, workflowId: String): Boolean =
  connection.prepareStatement("SELECT 1 FROM goal_run_sessions WHERE workflow_id = ?").use { statement ->
    statement.bind(workflowId)
    statement.executeQuery().use(ResultSet::next)
  }
