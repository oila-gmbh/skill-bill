package skillbill.db.core

import java.sql.Connection

@Suppress("TooManyFunctions")
internal object DatabaseColumnMigrations {
  private val safeIdentifierPattern = Regex("^[a-z_][a-z0-9_]*$")

  fun apply(connection: Connection) {
    ensureFeatureVerifyWorkflowColumns(connection)
    ensureReviewRunColumns(connection)
    ensureFindingColumns(connection)
    backfillReviewSessionIds(connection)
    ensureFeatureImplementSessionColumns(connection)
    ensureFeatureVerifySessionColumns(connection)
    ensureColumn(connection, "quality_check_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "quality_check_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "quality_check_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "routed_skill", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "detected_stack", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "scope_type", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "initial_failure_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "quality_check_sessions", "final_failure_count", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "iterations", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "result", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "failing_check_names", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "unsupported_reason", "TEXT NOT NULL DEFAULT ''")
    ensureFeatureTaskRuntimeSessionColumns(connection)
    // apply() is wired both as gated migration version 1 (which runs before version 3 creates
    // goal_subtask_events) and unconditionally on every startup. Skip the agent-attribution column
    // heal until the table exists so the early migration-1 pass is a no-op; the unconditional startup
    // pass heals it once version 3 has created the table (existing DBs gain the columns there).
    val goalSubtaskEventsExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_subtask_events'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalSubtaskEventsExists) {
      ensureColumn(connection, "goal_subtask_events", "finalizing_agent_id", "TEXT")
      ensureColumn(connection, "goal_subtask_events", "participating_agent_ids", "TEXT NOT NULL DEFAULT '[]'")
      ensureColumn(connection, "goal_subtask_events", "boundary_history_value", "TEXT NOT NULL DEFAULT 'none'")
      ensureColumn(connection, "goal_subtask_events", "boundary_history_written", "INTEGER NOT NULL DEFAULT 0")
    }
    val goalRunSessionsExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_run_sessions'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalRunSessionsExists) {
      ensureColumn(connection, "goal_run_sessions", "mode", "TEXT NOT NULL DEFAULT 'runtime'")
      ensureColumn(connection, "goal_run_sessions", "stop_reason", "TEXT")
    }
    val goalIssueProgressExists = connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'goal_issue_progress'",
    ).use { statement -> statement.executeQuery().use { resultSet -> resultSet.next() } }
    if (goalIssueProgressExists) {
      ensureColumn(connection, "goal_issue_progress", "finished_event_emitted_at", "TEXT")
    }
  }

  private fun ensureFeatureTaskRuntimeSessionColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "feature_task_runtime_sessions",
      columnName = "review_fix_iteration_count",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_task_runtime_sessions",
      columnName = "audit_gap_iteration_count",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureColumn(connection, "feature_task_runtime_sessions", "estimated_phase_tokens_json", "TEXT")
    ensureColumn(connection, "feature_task_runtime_sessions", "estimated_total_tokens", "INTEGER")
  }

  private fun ensureFeatureVerifyWorkflowColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_workflows",
      columnName = "workflow_name",
      definition = "TEXT NOT NULL DEFAULT 'bill-feature-verify'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_workflows",
      columnName = "contract_version",
      definition = "TEXT NOT NULL DEFAULT '${DbConstants.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION}'",
    )
  }

  private fun ensureReviewRunColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_session_id",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_finished_at",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_finished_event_emitted_at",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "specialist_reviews",
      definition = "TEXT NOT NULL DEFAULT ''",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "orchestrated_run",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
  }

  private fun ensureFindingColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "findings",
      columnName = "issue_category",
      definition = "TEXT NOT NULL DEFAULT 'other'",
    )
  }

  private fun backfillReviewSessionIds(connection: Connection) {
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET review_session_id = review_run_id
      WHERE review_session_id IS NULL OR review_session_id = ''
      """.trimIndent(),
    ).use { statement ->
      statement.executeUpdate()
    }
  }

  private fun ensureFeatureImplementSessionColumns(connection: Connection) {
    ensureColumn(connection, "feature_implement_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "feature_implement_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "feature_implement_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "source",
      definition = "TEXT NOT NULL DEFAULT 'production'",
    )
    ensureColumn(connection, "feature_implement_sessions", "issue_key_provided", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "issue_key_type", "TEXT NOT NULL DEFAULT 'none'")
    ensureColumn(connection, "feature_implement_sessions", "spec_input_types", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "spec_word_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "feature_size", "TEXT NOT NULL DEFAULT 'SMALL'")
    ensureColumn(connection, "feature_implement_sessions", "feature_name", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "rollout_needed", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "acceptance_criteria_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "open_questions_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "spec_summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "completion_status", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "plan_correction_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_task_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_phase_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "feature_flag_used", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "feature_flag_pattern", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "files_created", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "files_modified", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "tasks_completed", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "review_iterations", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "audit_result", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "audit_iterations", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "validation_result", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "boundary_history_written", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "pr_created", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_deviation_notes", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "boundary_history_value",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "child_steps_json",
      definition = "TEXT NOT NULL DEFAULT ''",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureColumn(connection, "feature_implement_sessions", "estimated_phase_tokens_json", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "estimated_total_tokens", "INTEGER")
  }

  private fun ensureFeatureVerifySessionColumns(connection: Connection) {
    ensureColumn(connection, "feature_verify_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "feature_verify_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "feature_verify_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "acceptance_criteria_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_verify_sessions", "rollout_relevant", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_verify_sessions", "spec_summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_verify_sessions", "feature_flag_audit_performed", "INTEGER")
    ensureColumn(connection, "feature_verify_sessions", "review_iterations", "INTEGER")
    ensureColumn(connection, "feature_verify_sessions", "audit_result", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "completion_status", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "gaps_found", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "history_relevance",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "history_helpfulness",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
  }

  private fun ensureColumn(connection: Connection, tableName: String, columnName: String, definition: String) {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    if (tableColumnNames(connection = connection, tableName = tableName).contains(columnName)) {
      return
    }
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
    }
  }

  private fun backfillBlankColumn(connection: Connection, tableName: String, columnName: String, expression: String) {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE $tableName
        SET $columnName = $expression
        WHERE $columnName IS NULL OR $columnName = ''
        """.trimIndent(),
      )
    }
  }

  private fun tableColumnNames(connection: Connection, tableName: String): Set<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }
}
