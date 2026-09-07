package skillbill.db.telemetry

import skillbill.telemetry.model.GoalIssueFinishedRecord
import java.sql.Connection

internal fun recoverGoalIssueProgress(
  connection: Connection,
  record: GoalIssueFinishedRecord,
): GoalIssueFinishedSaveOutcome {
  val history = loadRecoveredGoalSegments(connection, record)
  if (history.isEmpty()) {
    return GoalIssueFinishedSaveOutcome(false, "no persisted goal segment history exists")
  }
  if (history.any { it.workflowId.isBlank() || it.startedAt.isBlank() }) {
    return GoalIssueFinishedSaveOutcome(false, "persisted goal segment history has a blank identity or start")
  }
  persistRecoveredGoalProgress(connection, record, history)
  return GoalIssueFinishedSaveOutcome(true)
}

private fun loadRecoveredGoalSegments(
  connection: Connection,
  record: GoalIssueFinishedRecord,
): List<RecoveredGoalSegment> = connection.prepareStatement(
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

private fun persistRecoveredGoalProgress(
  connection: Connection,
  record: GoalIssueFinishedRecord,
  history: List<RecoveredGoalSegment>,
) {
  val firstStartedAt = history.minOf { it.startedAt }
  val latest = history.maxWith(compareBy<RecoveredGoalSegment> { it.startedAt }.thenBy { it.workflowId })
  connection.prepareStatement(
    """
    INSERT INTO goal_issue_progress (
      parent_workflow_id, issue_key, total_invocations, total_blocks, total_resumes,
      first_started_at, last_activity_at, latest_segment_workflow_id,
      last_blocked_at, last_blocked_segment_workflow_id, mode, status,
      state_entered_at, state_entered_at_estimated
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, 0)
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
      latest.startedAt,
    )
    statement.executeUpdate()
  }
}

internal fun goalIssueProgressExists(connection: Connection, parentWorkflowId: String, issueKey: String): Boolean =
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
