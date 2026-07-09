package skillbill.db.telemetry

import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection
import java.util.logging.Logger

enum class GoalStartedSaveOutcome { INSERTED, DUPLICATE }

enum class GoalFinishedSaveOutcome { FIRST_TERMINAL, DUPLICATE }

data class GoalIssueFinishedSaveOutcome(val persisted: Boolean, val suppressionReason: String? = null)

fun saveGoalStarted(connection: Connection, record: GoalStartedRecord): GoalStartedSaveOutcome {
  val inserted = connection.prepareStatement(
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, feature_name, subtask_total, resumed, started_at, mode
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(workflow_id) DO NOTHING
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.workflowId,
      record.issueKey,
      record.featureName,
      record.subtaskTotal,
      record.resumed.toSqlInt(),
      record.startedAt,
      record.mode,
    )
    statement.executeUpdate() > 0
  }
  return if (inserted) GoalStartedSaveOutcome.INSERTED else GoalStartedSaveOutcome.DUPLICATE
}

fun saveGoalFinished(connection: Connection, record: GoalFinishedRecord): GoalFinishedSaveOutcome {
  if (goalRunSessionExists(connection, record.workflowId)) {
    return if (updateGoalFinished(connection, record)) {
      GoalFinishedSaveOutcome.FIRST_TERMINAL
    } else {
      GoalFinishedSaveOutcome.DUPLICATE
    }
  } else {
    insertGoalFinished(connection, record)
    return GoalFinishedSaveOutcome.FIRST_TERMINAL
  }
}

private fun updateGoalFinished(connection: Connection, record: GoalFinishedRecord): Boolean =
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
      subtasks_skipped = ?,
      mode = ?,
      stop_reason = ?
    WHERE workflow_id = ? AND status IS NULL AND finished_at IS NULL
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
      record.mode,
      record.stopReason,
      record.workflowId,
    )
    statement.executeUpdate() > 0
  }

private fun insertGoalFinished(connection: Connection, record: GoalFinishedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, started_at, status, finished_at,
      finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped, mode, stop_reason
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
      record.mode,
      record.stopReason,
    )
    statement.executeUpdate()
  }
}

fun recordGoalIssueSegmentStarted(connection: Connection, segment: GoalIssueSegmentStart) {
  connection.prepareStatement(
    """
    INSERT INTO goal_issue_progress (
      parent_workflow_id, issue_key, total_invocations, total_resumes, first_started_at,
      last_activity_at, latest_segment_workflow_id, mode
    ) VALUES (?, ?, 1, ?, ?, ?, ?, ?)
    ON CONFLICT(parent_workflow_id, issue_key) DO UPDATE SET
      total_invocations = goal_issue_progress.total_invocations + 1,
      total_resumes = goal_issue_progress.total_resumes + excluded.total_resumes,
      first_started_at = COALESCE(goal_issue_progress.first_started_at, excluded.first_started_at),
      last_activity_at = excluded.last_activity_at,
      latest_segment_workflow_id = excluded.latest_segment_workflow_id,
      mode = excluded.mode
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      segment.parentWorkflowId,
      segment.issueKey,
      segment.resumed.toSqlInt(),
      segment.startedAt,
      segment.startedAt,
      segment.workflowId,
      segment.mode,
    )
    statement.executeUpdate()
  }
}

data class GoalIssueSegmentStart(
  val parentWorkflowId: String,
  val issueKey: String,
  val workflowId: String,
  val startedAt: String,
  val resumed: Boolean,
  val mode: String,
)

fun recordGoalIssueBlockedSegment(
  connection: Connection,
  parentWorkflowId: String,
  issueKey: String,
  workflowId: String,
) {
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET total_blocks = total_blocks + 1,
        last_activity_at = CURRENT_TIMESTAMP,
        last_blocked_at = CURRENT_TIMESTAMP,
        last_blocked_segment_workflow_id = ?
    WHERE parent_workflow_id = ? AND issue_key = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(workflowId, parentWorkflowId, issueKey)
    statement.executeUpdate()
  }
}

fun saveGoalIssueFinished(connection: Connection, record: GoalIssueFinishedRecord): GoalIssueFinishedSaveOutcome {
  if (!goalIssueProgressExists(connection, record.parentWorkflowId, record.issueKey)) {
    val recovered = recoverGoalIssueProgress(connection, record)
    if (!recovered.persisted) {
      goalTelemetryLogger.severe(
        "Suppressed goal_issue_finished for ${record.parentWorkflowId}/${record.issueKey}: " +
          recovered.suppressionReason,
      )
      return recovered
    }
  }
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress SET
      status = ?, subtasks_complete = ?, subtasks_blocked = ?, subtasks_skipped = ?,
      finished_at = ?, mode = ?
    WHERE parent_workflow_id = ? AND issue_key = ? AND finished_event_emitted_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.status,
      record.subtasksComplete,
      record.subtasksBlocked,
      record.subtasksSkipped,
      record.finishedAt,
      record.mode,
      record.parentWorkflowId,
      record.issueKey,
    )
    val updated = statement.executeUpdate() > 0
    return GoalIssueFinishedSaveOutcome(
      persisted = updated,
      suppressionReason = if (updated) null else "issue terminal event was already emitted",
    )
  }
}

private fun recoverGoalIssueProgress(
  connection: Connection,
  record: GoalIssueFinishedRecord,
): GoalIssueFinishedSaveOutcome {
  val history = connection.prepareStatement(
    """
    SELECT workflow_id, started_at, resumed, status
    FROM goal_run_sessions
    WHERE issue_key = ?
      AND substr(workflow_id, 1, length(?) + 5) = ? || ':seg:'
    ORDER BY datetime(started_at), workflow_id
    """.trimIndent(),
  ).use { statement ->
    statement.bind(record.issueKey, record.parentWorkflowId, record.parentWorkflowId)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            RecoveredGoalSegment(
              workflowId = resultSet.getString("workflow_id"),
              startedAt = resultSet.getString("started_at"),
              resumed = resultSet.getInt("resumed") != 0,
              status = resultSet.getString("status"),
            ),
          )
        }
      }
    }
  }
  if (history.isEmpty()) {
    return GoalIssueFinishedSaveOutcome(false, "no persisted goal segment history exists")
  }
  if (history.any { it.workflowId.isBlank() || it.startedAt.isBlank() }) {
    return GoalIssueFinishedSaveOutcome(false, "persisted goal segment history has a blank identity or start")
  }
  val firstStartedAt = history.minOf { it.startedAt }
  val latest = history.maxWith(compareBy<RecoveredGoalSegment> { it.startedAt }.thenBy { it.workflowId })
  connection.prepareStatement(
    """
    INSERT INTO goal_issue_progress (
      parent_workflow_id, issue_key, total_invocations, total_blocks, total_resumes,
      first_started_at, last_activity_at, latest_segment_workflow_id,
      last_blocked_at, last_blocked_segment_workflow_id, mode
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    val latestBlocked = history.filter { it.status == "blocked" }
      .maxWithOrNull(compareBy<RecoveredGoalSegment> { it.startedAt }.thenBy { it.workflowId })
    statement.bind(
      record.parentWorkflowId,
      record.issueKey,
      history.size,
      history.count { it.status == "blocked" },
      history.count { it.resumed },
      firstStartedAt,
      latest.startedAt,
      latest.workflowId,
      latestBlocked?.startedAt,
      latestBlocked?.workflowId,
      record.mode,
    )
    statement.executeUpdate()
  }
  return GoalIssueFinishedSaveOutcome(true)
}

private fun goalIssueProgressExists(connection: Connection, parentWorkflowId: String, issueKey: String): Boolean =
  connection.prepareStatement(
    "SELECT 1 FROM goal_issue_progress WHERE parent_workflow_id = ? AND issue_key = ?",
  ).use { statement ->
    statement.bind(parentWorkflowId, issueKey)
    statement.executeQuery().use { it.next() }
  }

private data class RecoveredGoalSegment(
  val workflowId: String,
  val startedAt: String,
  val resumed: Boolean,
  val status: String?,
)

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
    statement.executeQuery().use { it.next() }
  }

private val goalTelemetryLogger: Logger = Logger.getLogger("skillbill.telemetry.goal")
