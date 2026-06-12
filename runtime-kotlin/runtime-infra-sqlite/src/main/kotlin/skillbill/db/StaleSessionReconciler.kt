package skillbill.db

import java.sql.Connection

const val STALE_SESSION_THRESHOLD_SECONDS: Long = 28_800L

fun reconcileStaleFeatureImplementSessions(
  connection: Connection,
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int = reconcileStaleSessionsInTable(connection, "feature_implement_sessions", thresholdSeconds)

fun reconcileStaleFeatureTaskRuntimeSessions(
  connection: Connection,
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int = reconcileStaleSessionsInTable(connection, "feature_task_runtime_sessions", thresholdSeconds)

private fun reconcileStaleSessionsInTable(connection: Connection, tableName: String, thresholdSeconds: Long): Int =
  connection.prepareStatement(
    """
  UPDATE $tableName
  SET completion_status = 'stale',
      finished_at = CURRENT_TIMESTAMP
  WHERE finished_at IS NULL
    AND started_at <= datetime('now', '-$thresholdSeconds seconds')
    """.trimIndent(),
  ).use { it.executeUpdate() }
