package skillbill.db.core

import java.sql.Connection

internal object DatabaseColumnMigrationsWorkList {
  fun applyWorkListMetadata(connection: Connection) {
    applyWorkListMetadata(connection, recoverIssueKeys = true)
  }

  fun healWorkListMetadata(connection: Connection) {
    connection.inImmediateTransaction {
      applyWorkListMetadata(this, recoverIssueKeys = false)
    }
  }

  private fun applyWorkListMetadata(connection: Connection, recoverIssueKeys: Boolean) {
    val workflowColumnsHealed = ensureWorkListWorkflowColumns(connection)
    val goalColumnsHealed = ensureGoalWorkListColumns(connection)
    if (recoverIssueKeys || workflowColumnsHealed || goalColumnsHealed) {
      recoverWorkListIssueKeys(connection)
    }
  }

  fun recoverWorkListIssueKeys(connection: Connection) {
    DatabaseColumnMigrationsWorkListRecovery.recoverWorkListIssueKeys(connection)
  }

  private fun ensureWorkListWorkflowColumns(connection: Connection): Boolean {
    var columnsHealed = false
    listOf("feature_task_workflows", "feature_verify_workflows").forEach { tableName ->
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(
        connection,
        tableName,
        "issue_key",
        "TEXT",
      ) || columnsHealed
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(
        connection,
        tableName,
        "state_entered_at",
        "TEXT",
      ) || columnsHealed
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(
        connection,
        tableName,
        "state_entered_at_estimated",
        "INTEGER",
      ) || columnsHealed
      connection.createStatement().use { statement ->
        statement.execute(
          """
          UPDATE $tableName
          SET state_entered_at = CASE
                WHEN state_entered_at IS NULL OR state_entered_at = '' THEN COALESCE(
                  NULLIF(finished_at, ''), NULLIF(updated_at, ''), NULLIF(started_at, '')
                )
                ELSE state_entered_at
              END,
              state_entered_at_estimated = CASE
                WHEN state_entered_at IS NULL OR state_entered_at = ''
                     OR state_entered_at_estimated IS NULL THEN 1
                ELSE state_entered_at_estimated
              END
          WHERE state_entered_at_estimated IS NULL
             OR (
               (state_entered_at IS NULL OR state_entered_at = '')
               AND (
                 state_entered_at_estimated != 1
                 OR NULLIF(finished_at, '') IS NOT NULL
                 OR NULLIF(updated_at, '') IS NOT NULL
                 OR NULLIF(started_at, '') IS NOT NULL
               )
             )
          """.trimIndent(),
        )
      }
    }
    return columnsHealed
  }

  private fun ensureGoalWorkListColumns(connection: Connection): Boolean {
    if (!DatabaseColumnMigrations.tableExists(connection, "goal_issue_progress")) {
      return false
    }
    val stateEnteredAtAdded = DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_issue_progress",
      "state_entered_at",
      "TEXT",
    )
    val estimatedAdded = DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "goal_issue_progress",
      "state_entered_at_estimated",
      "INTEGER",
    )
    healGoalIssueProgressStateEntries(connection)
    return stateEnteredAtAdded || estimatedAdded
  }

  private fun healGoalIssueProgressStateEntries(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE goal_issue_progress
        SET status = CASE
              WHEN status IS NOT NULL AND status != '' THEN status
              WHEN last_blocked_segment_workflow_id IS NOT NULL
                   AND last_blocked_segment_workflow_id = latest_segment_workflow_id THEN 'blocked'
              ELSE 'running'
            END,
            state_entered_at = COALESCE(
              NULLIF(state_entered_at, ''), NULLIF(finished_at, ''), NULLIF(last_activity_at, ''),
              NULLIF(first_started_at, '')
            ),
            state_entered_at_estimated = CASE
              WHEN state_entered_at IS NULL OR state_entered_at = ''
                   OR state_entered_at_estimated IS NULL THEN 1
              ELSE COALESCE(state_entered_at_estimated, 0)
            END
        WHERE status IS NULL OR status = ''
           OR state_entered_at_estimated IS NULL
           OR (
             (state_entered_at IS NULL OR state_entered_at = '')
             AND (
               state_entered_at_estimated != 1
               OR NULLIF(finished_at, '') IS NOT NULL
               OR NULLIF(last_activity_at, '') IS NOT NULL
               OR NULLIF(first_started_at, '') IS NOT NULL
             )
           )
        """.trimIndent(),
      )
    }
  }
}
