package skillbill.db.core

import skillbill.db.telemetry.bind
import skillbill.db.telemetry.emitFeatureImplementFinished
import skillbill.db.telemetry.emitFeatureTaskRuntimeFinished
import skillbill.db.telemetry.emitFeatureVerifyFinished
import skillbill.db.telemetry.emitGoalIssueFinished
import skillbill.db.telemetry.emitQualityCheckFinished
import skillbill.ports.persistence.model.TelemetryReconciliationResult
import java.sql.Connection

const val STALE_SESSION_THRESHOLD_SECONDS: Long = 28_800L
const val STALE_GOAL_ISSUE_ABANDONMENT_DAYS: Long = 14L

private data class LifecycleReconciliationTarget(
  val tableName: String,
  val terminalColumn: String,
  val terminalValue: String,
  val emitFinished: (String) -> Unit,
)

fun reconcileStaleTelemetrySessions(
  connection: Connection,
  level: String,
  sessionThresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
  goalIssueAbandonmentDays: Long = STALE_GOAL_ISSUE_ABANDONMENT_DAYS,
): TelemetryReconciliationResult {
  val featureImplement = reconcileLifecycleTable(
    connection = connection,
    target = LifecycleReconciliationTarget(
      tableName = "feature_implement_sessions",
      terminalColumn = "completion_status",
      terminalValue = "stale",
    ) { sessionId -> emitFeatureImplementFinished(connection, sessionId, level) },
    thresholdSeconds = sessionThresholdSeconds,
  )
  val featureTaskRuntime = reconcileLifecycleTable(
    connection = connection,
    target = LifecycleReconciliationTarget(
      tableName = "feature_task_runtime_sessions",
      terminalColumn = "completion_status",
      terminalValue = "stale",
    ) { sessionId -> emitFeatureTaskRuntimeFinished(connection, sessionId, level) },
    thresholdSeconds = sessionThresholdSeconds,
  )
  val featureVerify = reconcileLifecycleTable(
    connection = connection,
    target = LifecycleReconciliationTarget(
      tableName = "feature_verify_sessions",
      terminalColumn = "completion_status",
      terminalValue = "stale",
    ) { sessionId -> emitFeatureVerifyFinished(connection, sessionId, level) },
    thresholdSeconds = sessionThresholdSeconds,
  )
  val qualityCheck = reconcileLifecycleTable(
    connection = connection,
    target = LifecycleReconciliationTarget(
      tableName = "quality_check_sessions",
      terminalColumn = "result",
      terminalValue = "stale",
    ) { sessionId -> emitQualityCheckFinished(connection, sessionId, level) },
    thresholdSeconds = sessionThresholdSeconds,
  )
  val goalIssueAbandoned = reconcileAbandonedGoalIssues(connection, goalIssueAbandonmentDays)
  val lifecycleTerminalEvents = featureImplement + featureTaskRuntime + featureVerify + qualityCheck
  return TelemetryReconciliationResult(
    featureImplementSessions = featureImplement,
    featureTaskRuntimeSessions = featureTaskRuntime,
    featureVerifySessions = featureVerify,
    qualityCheckSessions = qualityCheck,
    goalIssueAbandonedSessions = goalIssueAbandoned,
    emittedTerminalEvents = lifecycleTerminalEvents + goalIssueAbandoned,
  )
}

fun reconcileStaleFeatureImplementSessions(
  connection: Connection,
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int = reconcileLifecycleTable(
  connection = connection,
  target = LifecycleReconciliationTarget(
    tableName = "feature_implement_sessions",
    terminalColumn = "completion_status",
    terminalValue = "stale",
  ) { sessionId -> emitFeatureImplementFinished(connection, sessionId, "anonymous") },
  thresholdSeconds = thresholdSeconds,
)

fun reconcileStaleFeatureTaskRuntimeSessions(
  connection: Connection,
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int = reconcileLifecycleTable(
  connection = connection,
  target = LifecycleReconciliationTarget(
    tableName = "feature_task_runtime_sessions",
    terminalColumn = "completion_status",
    terminalValue = "stale",
  ) { sessionId -> emitFeatureTaskRuntimeFinished(connection, sessionId, "anonymous") },
  thresholdSeconds = thresholdSeconds,
)

private fun reconcileLifecycleTable(
  connection: Connection,
  target: LifecycleReconciliationTarget,
  thresholdSeconds: Long,
): Int {
  val sessionIds = staleSessionIds(connection, target.tableName, thresholdSeconds)
  sessionIds.forEach { sessionId ->
    markLifecycleSessionStale(connection, target, sessionId)
    target.emitFinished(sessionId)
  }
  return sessionIds.size
}

private fun staleSessionIds(connection: Connection, tableName: String, thresholdSeconds: Long): List<String> =
  connection.prepareStatement(
    """
    SELECT session_id
    FROM $tableName
    WHERE finished_at IS NULL
      AND finished_event_emitted_at IS NULL
      AND started_at <= datetime('now', '-' || ? || ' seconds')
    ORDER BY started_at, session_id
    """.trimIndent(),
  ).use { statement ->
    statement.bind(thresholdSeconds)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.getString("session_id"))
        }
      }
    }
  }

private fun markLifecycleSessionStale(
  connection: Connection,
  target: LifecycleReconciliationTarget,
  sessionId: String,
) {
  connection.prepareStatement(
    """
    UPDATE ${target.tableName}
    SET ${target.terminalColumn} = ?,
        finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND finished_at IS NULL
      AND finished_event_emitted_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bind(target.terminalValue, sessionId)
    statement.executeUpdate()
  }
}

private fun reconcileAbandonedGoalIssues(connection: Connection, abandonmentDays: Long): Int {
  val goals = abandonedGoalIssues(connection, abandonmentDays)
  goals.forEach { goal ->
    markGoalIssueAbandoned(connection, goal)
    emitGoalIssueFinished(connection, goal.parentWorkflowId, goal.issueKey)
  }
  return goals.size
}

private fun abandonedGoalIssues(connection: Connection, abandonmentDays: Long): List<GoalIssueIdentity> =
  connection.prepareStatement(
    """
    SELECT parent_workflow_id, issue_key
    FROM goal_issue_progress
    WHERE finished_at IS NULL
      AND finished_event_emitted_at IS NULL
      AND last_blocked_at IS NOT NULL
      AND datetime(last_blocked_at) <= datetime('now', '-' || ? || ' days')
      AND (last_activity_at IS NULL OR datetime(last_activity_at) <= datetime(last_blocked_at))
    ORDER BY last_blocked_at, parent_workflow_id, issue_key
    """.trimIndent(),
  ).use { statement ->
    statement.bind(abandonmentDays)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            GoalIssueIdentity(
              parentWorkflowId = resultSet.getString("parent_workflow_id"),
              issueKey = resultSet.getString("issue_key"),
            ),
          )
        }
      }
    }
  }

private fun markGoalIssueAbandoned(connection: Connection, goal: GoalIssueIdentity) {
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET status = 'abandoned',
        subtasks_complete = COALESCE(subtasks_complete, 0),
        subtasks_blocked = COALESCE(subtasks_blocked, total_blocks),
        subtasks_skipped = COALESCE(subtasks_skipped, 0),
        finished_at = CURRENT_TIMESTAMP
    WHERE parent_workflow_id = ?
      AND issue_key = ?
      AND finished_at IS NULL
      AND finished_event_emitted_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bind(goal.parentWorkflowId, goal.issueKey)
    statement.executeUpdate()
  }
}

private data class GoalIssueIdentity(
  val parentWorkflowId: String,
  val issueKey: String,
)
