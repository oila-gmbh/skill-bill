package skillbill.db.core

import java.sql.Connection

@Suppress("TooManyFunctions")
internal object DatabaseColumnMigrations {
  private val safeIdentifierPattern = Regex("^[a-z_][a-z0-9_]*$")

  fun apply(connection: Connection) {
    ensureFeatureVerifyWorkflowColumns(connection)
    ensureReviewRunColumns(connection)
    ensureReviewRunLaneDispositionColumns(connection)
    ensureFindingColumns(connection)
    ensureUnaddressedFindingColumns(connection)
    ensureReviewFindingOutcomeColumns(connection)
    backfillReviewSessionIds(connection)
    ReviewAttributionBackfillMigration.backfillExecutionModes(connection)
    ensureFeatureImplementSessionColumns(connection)
    ensureFeatureVerifySessionColumns(connection)
    ensureQualityCheckSessionColumns(connection)
    ensureFeatureTaskRuntimeSessionColumns(connection)
    ensureColumn(connection, "feature_task_workflows", "interruption_reason", "TEXT")
    // Must stay in this pass, which runs after DatabaseMigrations.apply: TelemetryOutboxLastErrorMigration
    // rebuilds telemetry_outbox from an explicit column list and would silently drop the column.
    ensureColumn(connection, "telemetry_outbox", "skill_bill_version", "TEXT")
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
      ensureColumn(connection, "goal_issue_progress", "last_activity_at", "TEXT")
      ensureColumn(connection, "goal_issue_progress", "last_blocked_at", "TEXT")
      ensureColumn(connection, "goal_issue_progress", "latest_segment_workflow_id", "TEXT")
      ensureColumn(connection, "goal_issue_progress", "last_blocked_segment_workflow_id", "TEXT")
    }
    ensureReconciliationIndexes(connection)
  }

  private fun ensureUnaddressedFindingColumns(connection: Connection) {
    if (!tableExists(connection, "unaddressed_findings")) return
    ensureColumn(connection, "unaddressed_findings", "issue_key", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "subtask_id", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "unaddressed_findings", "severity", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "issue_category", "TEXT NOT NULL DEFAULT 'other'")
    ensureColumn(connection, "unaddressed_findings", "location", "TEXT NOT NULL DEFAULT '<unknown>'")
    ensureColumn(connection, "unaddressed_findings", "summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "recorded_at", "TEXT NOT NULL DEFAULT ''")
    ensureReviewFindingOutcomeKeyColumns(connection)
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_issue " +
          "ON unaddressed_findings(issue_key, subtask_id, review_pass_number)",
      )
    }
  }

  /**
   * The shared review-run/finding key on the workflow-loop ledger. Both columns are nullable with no
   * default: a pass for which no review run was imported keeps them NULL and is read as unresolved,
   * rather than being bucketed to a guessed review run.
   */
  fun ensureReviewFindingOutcomeKeyColumns(connection: Connection) {
    if (!tableExists(connection, "unaddressed_findings")) return
    ensureColumn(connection, "unaddressed_findings", "review_run_id", "TEXT")
    ensureColumn(connection, "unaddressed_findings", "finding_id", "TEXT")
    // The index lives here, not in the shared schema statement list: on a pre-existing store the
    // CREATE TABLE is a no-op and the index would be created before these columns exist.
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_run " +
          "ON unaddressed_findings(review_run_id, finding_id)",
      )
    }
  }

  /**
   * Content-derived cross-pass identity on the durable outcome table. Nullable with no backfill: a
   * row written before this column existed has no location or summary left to derive a key from —
   * `unaddressed_findings` is retracted on every pass — so it stays NULL and is excluded from
   * cross-pass reconciliation rather than being matched on its per-run positional finding id.
   */
  fun ensureReviewFindingOutcomeColumns(connection: Connection) {
    if (!tableExists(connection, "review_finding_outcomes")) return
    ensureColumn(connection, "review_finding_outcomes", "finding_key", "TEXT")
    // Declared here rather than in the shared schema list: on an existing store the CREATE TABLE is a
    // no-op and the index would be created before the column exists.
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_review_finding_outcomes_key " +
          "ON review_finding_outcomes(workflow_id, finding_key)",
      )
    }
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
      columnsHealed = ensureColumn(connection, tableName, "issue_key", "TEXT") || columnsHealed
      columnsHealed = ensureColumn(connection, tableName, "state_entered_at", "TEXT") || columnsHealed
      columnsHealed = ensureColumn(connection, tableName, "state_entered_at_estimated", "INTEGER") || columnsHealed
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
    val stateEnteredAtAdded = ensureColumn(connection, "goal_issue_progress", "state_entered_at", "TEXT")
    val estimatedAdded = ensureColumn(
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

  internal fun reviewRunColumnNames(connection: Connection): Set<String> = tableColumnNames(connection, "review_runs")

  private fun tableExists(connection: Connection, tableName: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
  ).use { statement ->
    statement.setString(1, tableName)
    statement.executeQuery().use { resultSet -> resultSet.next() }
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

  private fun ensureQualityCheckSessionColumns(connection: Connection) {
    ensureColumn(connection, "quality_check_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "quality_check_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "quality_check_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "routed_skill", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "detected_stack", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "fallback", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "quality_check_sessions", "fallback_reason", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "scope_type", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "initial_failure_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "quality_check_sessions", "final_failure_count", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "iterations", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "result", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "failing_check_names", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "unsupported_reason", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(
      connection = connection,
      tableName = "quality_check_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
  }

  private fun ensureFeatureTaskRuntimeSessionColumns(connection: Connection) {
    ensureColumn(connection, "feature_task_runtime_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "feature_task_runtime_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "feature_task_runtime_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_task_runtime_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "feature_task_runtime_sessions", "finished_event_emitted_at", "TEXT")
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
    listOf(
      "audit_first_pass_convergence",
      "audit_recurring_gap_count",
      "audit_new_gap_count",
      "audit_attempted_repair_item_count",
      "audit_resolved_repair_item_count",
    ).forEach { column ->
      ensureColumn(connection, "feature_task_runtime_sessions", column, "INTEGER NOT NULL DEFAULT 0")
    }
    listOf(
      "regeneration_activation_count",
      "regeneration_attempt_count",
    ).forEach { column ->
      ensureColumn(connection, "feature_task_runtime_sessions", column, "INTEGER NOT NULL DEFAULT 0")
    }
    ensureColumn(connection, "feature_task_runtime_sessions", "regeneration_outcome_counts_json", "TEXT")
    ensureColumn(
      connection = connection,
      tableName = "feature_task_runtime_sessions",
      columnName = "crash_reconciliation_count",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureColumn(connection, "feature_task_runtime_sessions", "crash_reconciliation_reason_counts_json", "TEXT")
    ensureColumn(connection, "feature_task_runtime_sessions", "estimated_phase_tokens_json", "TEXT")
    ensureColumn(connection, "feature_task_runtime_sessions", "estimated_total_tokens", "INTEGER")
    ensureColumn(
      connection = connection,
      tableName = "feature_task_runtime_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
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

  internal fun ensureReviewRunColumns(connection: Connection) {
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
    // execution_mode ships in the fresh schema, but legacy stores predate it and backfillReviewExecutionModes
    // below updates it on every open. Heal the column first so opening a legacy store cannot fail on it.
    ensureColumn(connection, "review_runs", "execution_mode", "TEXT")
    ensureColumn(connection, "review_runs", "routed_skill_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_stack_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_scope_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_scope_detail", "TEXT")
    // Created here rather than in the base schema: a legacy store still lacks routed_skill_canonical
    // when createBaseSchema runs, so the index can only be declared once the column is healed.
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_review_runs_routed_skill_canonical " +
          "ON review_runs(routed_skill_canonical, review_run_id)",
      )
    }
  }

  private fun ensureFindingColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "findings",
      columnName = "issue_category",
      definition = "TEXT NOT NULL DEFAULT 'other'",
    )
    ensureFindingLaneColumns(connection)
  }

  // Per-finding lane attribution. Additive and nullable: a finding recorded before lane attribution
  // existed keeps a NULL lane rather than being guessed into a bucket.
  internal fun ensureFindingLaneColumns(connection: Connection) {
    if (!tableExists(connection, "findings")) return
    ensureColumn(connection, "findings", "lane_skill_name", "TEXT")
    ensureColumn(connection, "findings", "lane_area", "TEXT")
    ensureColumn(connection, "findings", "lane_pack_slug", "TEXT")
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_findings_lane ON findings(lane_skill_name, review_run_id)",
      )
    }
  }

  internal fun ensureReviewRunLaneDispositionColumns(connection: Connection) {
    if (!tableExists(connection, "review_run_lanes")) return
    ensureColumn(connection, "review_run_lanes", "review_disposition", "TEXT NOT NULL DEFAULT 'complete'")
    ensureColumn(connection, "review_run_lanes", "bundle_composition_digest", "TEXT")
    ensureColumn(connection, "review_run_lanes", "segment_accounting_json", "TEXT")
    ensureColumn(connection, "review_run_lanes", "unreviewed_segment_ids", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "review_run_lanes", "budget_dimension", "TEXT")
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
    backfillFeatureImplementStartedAt(connection)
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
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
  }

  private fun backfillFeatureImplementStartedAt(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE feature_implement_sessions
        SET started_at = COALESCE(
          (
            SELECT feature_task_workflows.started_at
            FROM feature_task_workflows
            WHERE feature_task_workflows.session_id = feature_implement_sessions.session_id
              AND feature_task_workflows.started_at IS NOT NULL
              AND feature_task_workflows.started_at != ''
            ORDER BY feature_task_workflows.started_at
            LIMIT 1
          ),
          CURRENT_TIMESTAMP
        )
        WHERE started_at IS NULL OR started_at = ''
        """.trimIndent(),
      )
    }
  }

  private fun ensureColumn(connection: Connection, tableName: String, columnName: String, definition: String): Boolean {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    if (tableColumnNames(connection = connection, tableName = tableName).contains(columnName)) {
      return false
    }
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
    }
    return true
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
