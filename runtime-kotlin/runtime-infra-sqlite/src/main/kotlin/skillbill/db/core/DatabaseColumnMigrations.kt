package skillbill.db.core

import java.sql.Connection


internal object DatabaseColumnMigrations {
  private val safeIdentifierPattern = Regex("^[a-z_][a-z0-9_]*$")

  fun apply(connection: Connection) {
    DatabaseColumnMigrationsEnsure.ensureFeatureVerifyWorkflowColumns(connection)
    DatabaseColumnMigrationsEnsure.ensureReviewRunColumns(connection)
    DatabaseReviewColumnMigrations.apply(connection)
    ReviewAttributionBackfillMigration.backfillExecutionModes(connection)
    DatabaseColumnMigrationsEnsure.ensureFeatureImplementSessionColumns(connection)
    DatabaseColumnMigrationsEnsure.ensureFeatureVerifySessionColumns(connection)
    DatabaseColumnMigrationsEnsure.ensureQualityCheckSessionColumns(connection)
    DatabaseColumnMigrationsEnsure.ensureFeatureTaskRuntimeSessionColumns(connection)
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "feature_task_workflows", "interruption_reason", "TEXT")
    // Must stay in this pass, which runs after DatabaseMigrations.apply: TelemetryOutboxLastErrorMigration
    // rebuilds telemetry_outbox from an explicit column list and would silently drop the column.
    DatabaseColumnMigrationsEnsure.ensureColumn(connection, "telemetry_outbox", "skill_bill_version", "TEXT")
    // apply() is wired both as gated migration version 1 (which runs before version 3 creates
    // goal_subtask_events) and unconditionally on every startup. Skip the agent-attribution column
    // heal until the table exists so the early migration-1 pass is a no-op; the unconditional startup
    // pass heals it once version 3 has created the table (existing DBs gain the columns there).
    val goalSubtaskEventsExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_subtask_events'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalSubtaskEventsExists) {
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_subtask_events", "finalizing_agent_id", "TEXT")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_subtask_events", "participating_agent_ids", "TEXT NOT NULL DEFAULT '[]'")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_subtask_events", "boundary_history_value", "TEXT NOT NULL DEFAULT 'none'")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_subtask_events", "boundary_history_written", "INTEGER NOT NULL DEFAULT 0")
    }
    val goalRunSessionsExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_run_sessions'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalRunSessionsExists) {
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_run_sessions", "mode", "TEXT NOT NULL DEFAULT 'runtime'")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_run_sessions", "stop_reason", "TEXT")
    }
    val goalIssueProgressExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_issue_progress'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalIssueProgressExists) {
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "finished_event_emitted_at", "TEXT")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "last_activity_at", "TEXT")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "last_blocked_at", "TEXT")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "latest_segment_workflow_id", "TEXT")
      DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "last_blocked_segment_workflow_id", "TEXT")
    }
    ensureReconciliationIndexes(connection)
  }

  /**
   * Unconditional on every open, like the column ensures in [apply], because the two diagnostic-store
   * rebuilds that came before it (versions 16 and 28) restate the pre-repair-turn DDL from an explicit
   * column list. A store whose ledger already records the gated version-29 row but whose table was
   * later rebuilt by one of those would otherwise keep the narrow key forever.
   *
   * Kept out of [apply] because that function is also wired as gated migration version 1 and therefore
   * runs inside an open transaction, where a nested `BEGIN IMMEDIATE` fails.
   */
  fun healDiagnosticEvidenceKeys(connection: Connection) {
    connection.inImmediateTransaction { rekeyDiagnosticEvidenceByRepairTurn(this) }
  }

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
    recoverRuntimeWorkflowIssueKeys(connection)
    recoverGoalContinuationWorkflowIssueKeys(connection)
    recoverDecompositionWorkflowIssueKeys(connection)
  }

  private fun ensureWorkListWorkflowColumns(connection: Connection): Boolean {
    var columnsHealed = false
    listOf("feature_task_workflows", "feature_verify_workflows").forEach { tableName ->
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(connection, tableName, "issue_key", "TEXT") || columnsHealed
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(connection, tableName, "state_entered_at", "TEXT") || columnsHealed
      columnsHealed = DatabaseColumnMigrationsEnsure.ensureColumn(connection, tableName, "state_entered_at_estimated", "INTEGER") || columnsHealed
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
    if (!tableExists(connection, "goal_issue_progress")) {
      return false
    }
    val stateEnteredAtAdded = DatabaseColumnMigrationsEnsure.ensureColumn(connection, "goal_issue_progress", "state_entered_at", "TEXT")
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

  // The `mode = 'prose'` branch here is RETAINED per SKILL-175 decisions
  // (runtime-kotlin/agent/decisions.md, "In-flight prose row policy"): this is a read-side
  // issue_key backfill, not a continuation-candidacy predicate, and removing it would leave
  // quarantined legacy prose rows permanently missing issue_key in work-list history. It is not
  // a new prose write path.
  private fun recoverGoalContinuationWorkflowIssueKeys(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE feature_task_workflows
        SET issue_key = CASE
          WHEN json_valid(artifacts_json) THEN trim(json_extract(artifacts_json, '$.goal_continuation.issue_key'))
          ELSE NULL
        END
        WHERE (issue_key IS NULL OR issue_key = '')
          AND CASE WHEN json_valid(artifacts_json) THEN
            json_type(artifacts_json, '$.goal_continuation') = 'object'
            AND json_type(artifacts_json, '$.goal_continuation.issue_key') = 'text'
            AND NULLIF(trim(json_extract(artifacts_json, '$.goal_continuation.issue_key')), '') IS NOT NULL
            AND json_type(artifacts_json, '$.goal_continuation.subtask_id') = 'integer'
            AND json_extract(artifacts_json, '$.goal_continuation.subtask_id') > 0
            AND json_type(artifacts_json, '$.goal_continuation.suppress_pr') IN ('true', 'false')
            AND (
              (mode = 'runtime'
                AND NULLIF(trim(json_extract(artifacts_json, '$.goal_continuation.goal_branch')), '') IS NOT NULL)
              OR (mode = 'prose' AND json_type(artifacts_json, '$.goal_continuation.enabled') = 'true')
            )
          ELSE 0 END
        """.trimIndent(),
      )
    }
  }

  private fun recoverDecompositionWorkflowIssueKeys(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE feature_task_workflows
        SET issue_key = trim(json_extract(artifacts_json, '$.decomposition_runtime.issue_key'))
        WHERE (issue_key IS NULL OR issue_key = '')
          AND json_valid(artifacts_json)
          AND json_type(artifacts_json, '$.decomposition_runtime') = 'object'
          AND json_type(artifacts_json, '$.decomposition_runtime.issue_key') = 'text'
          AND NULLIF(trim(json_extract(artifacts_json, '$.decomposition_runtime.issue_key')), '') IS NOT NULL
        """.trimIndent(),
      )
    }
  }

  private fun recoverRuntimeWorkflowIssueKeys(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE feature_task_workflows
        SET issue_key = (
          SELECT NULLIF(feature_task_runtime_sessions.issue_key, '')
          FROM feature_task_runtime_sessions
          WHERE feature_task_runtime_sessions.session_id = feature_task_workflows.session_id
        )
        WHERE (issue_key IS NULL OR issue_key = '')
          AND mode = 'runtime'
          AND EXISTS (
            SELECT 1 FROM feature_task_runtime_sessions
            WHERE feature_task_runtime_sessions.session_id = feature_task_workflows.session_id
              AND feature_task_runtime_sessions.issue_key IS NOT NULL
              AND feature_task_runtime_sessions.issue_key != ''
          )
        """.trimIndent(),
      )
    }
  }

  internal fun reviewRunsTableExists(connection: Connection): Boolean = tableExists(connection, "review_runs")

  internal fun reviewRunColumnNames(connection: Connection): Set<String> =
    DatabaseColumnMigrationsEnsure.tableColumnNames(connection, "review_runs")

  internal fun tableExists(connection: Connection, tableName: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
  ).use { statement ->
    statement.setString(1, tableName)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }

  internal fun ensureColumn(
    connection: Connection,
    tableName: String,
    columnName: String,
    definition: String,
  ): Boolean = DatabaseColumnMigrationsEnsure.ensureColumn(connection, tableName, columnName, definition)

  internal fun ensureReviewRunColumns(connection: Connection) {
    DatabaseColumnMigrationsEnsure.ensureReviewRunColumns(connection)
  }

  private fun ensureReconciliationIndexes(connection: Connection) {
    listOf(
      "CREATE INDEX IF NOT EXISTS idx_feature_implement_reconciliation_candidates " +
        "ON feature_implement_sessions(started_at, session_id) " +
        "WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL",
      "CREATE INDEX IF NOT EXISTS idx_feature_task_runtime_reconciliation_candidates " +
        "ON feature_task_runtime_sessions(started_at, session_id) " +
        "WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL",
      "CREATE INDEX IF NOT EXISTS idx_feature_verify_reconciliation_candidates " +
        "ON feature_verify_sessions(started_at, session_id) " +
        "WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL",
      "CREATE INDEX IF NOT EXISTS idx_quality_check_reconciliation_candidates " +
        "ON quality_check_sessions(started_at, session_id) " +
        "WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL",
      "CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_reconciliation_activity " +
        "ON feature_task_workflows(session_id, workflow_status, updated_at)",
      "CREATE INDEX IF NOT EXISTS idx_feature_verify_workflows_reconciliation_activity " +
        "ON feature_verify_workflows(session_id, workflow_status, updated_at)",
      "CREATE INDEX IF NOT EXISTS idx_goal_issue_reconciliation_candidates " +
        "ON goal_issue_progress(last_blocked_at, parent_workflow_id, issue_key) " +
        "WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL",
      "CREATE INDEX IF NOT EXISTS idx_telemetry_reconciliation_completed " +
        "ON telemetry_reconciliation_state(last_completed_at)",
    ).forEach { sql -> connection.createStatement().use { it.execute(sql) } }
  }
}
