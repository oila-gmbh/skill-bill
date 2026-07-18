package skillbill.db.core

import java.sql.Connection

internal object DatabaseSchema {
  val tableNames: Set<String> =
    setOf(
      "schema_migrations",
      "review_runs",
      "findings",
      "feedback_events",
      "learnings",
      "telemetry_outbox",
      "session_learnings",
      "quality_check_sessions",
      "feature_verify_sessions",
      "feature_implement_sessions",
      "feature_task_runtime_sessions",
      "feature_task_workflows",
      "feature_task_execution_identities",
      "feature_task_runtime_worker_leases",
      "feature_verify_workflows",
      "goal_run_sessions",
      "goal_subtask_events",
      "goal_issue_progress",
      "goal_planning_preparations",
      "goal_shared_preplans",
      "goal_subtask_plans",
      "telemetry_reconciliation_state",
    )

  val indexNames: Set<String> =
    setOf(
      "idx_feedback_events_run",
      "idx_learnings_scope",
      "idx_telemetry_outbox_pending",
      "idx_feature_task_workflows_updated",
      "idx_feature_task_identity_lookup",
      "idx_feature_implement_reconciliation_candidates",
      "idx_feature_task_runtime_reconciliation_candidates",
      "idx_feature_verify_reconciliation_candidates",
      "idx_quality_check_reconciliation_candidates",
      "idx_feature_task_workflows_reconciliation_activity",
      "idx_feature_verify_workflows_reconciliation_activity",
      "idx_goal_issue_reconciliation_candidates",
      "idx_goal_planning_preparations_lookup",
      "idx_goal_subtask_plans_ordered",
      "idx_telemetry_reconciliation_completed",
    )

  fun createBaseSchema(connection: Connection) {
    statements.forEach { statementSql ->
      connection.createStatement().use { statement ->
        statement.execute(statementSql)
      }
    }
  }

  private val statements: List<String> =
    listOf(
      """
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS review_runs (
        review_run_id TEXT PRIMARY KEY,
        review_session_id TEXT,
        routed_skill TEXT,
        detected_scope TEXT,
        detected_stack TEXT,
        execution_mode TEXT,
        source_path TEXT,
        raw_text TEXT NOT NULL,
        review_finished_at TEXT,
        review_finished_event_emitted_at TEXT,
        imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS findings (
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        severity TEXT NOT NULL,
        confidence TEXT NOT NULL,
        issue_category TEXT NOT NULL DEFAULT 'other',
        location TEXT NOT NULL,
        description TEXT NOT NULL,
        finding_text TEXT NOT NULL,
        PRIMARY KEY (review_run_id, finding_id),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feedback_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        event_type TEXT NOT NULL CHECK (
          event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
        ),
        note TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_feedback_events_run
        ON feedback_events(review_run_id, finding_id, id)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS learnings (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        scope TEXT NOT NULL CHECK (scope IN ('global', 'repo', 'skill')),
        scope_key TEXT NOT NULL DEFAULT '',
        title TEXT NOT NULL,
        rule_text TEXT NOT NULL,
        rationale TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL CHECK (status IN ('active', 'disabled')) DEFAULT 'active',
        source_review_run_id TEXT,
        source_finding_id TEXT,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CHECK ((source_review_run_id IS NULL) = (source_finding_id IS NULL)),
        FOREIGN KEY (source_review_run_id, source_finding_id)
          REFERENCES findings(review_run_id, finding_id)
          ON DELETE SET NULL
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_learnings_scope
        ON learnings(scope, scope_key, status)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS telemetry_outbox (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        event_name TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        synced_at TEXT,
        last_error TEXT NOT NULL DEFAULT ''
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_telemetry_outbox_pending
        ON telemetry_outbox(synced_at, id)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS session_learnings (
        review_session_id TEXT PRIMARY KEY,
        learnings_json TEXT NOT NULL,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS quality_check_sessions (
        session_id TEXT PRIMARY KEY,
        routed_skill TEXT NOT NULL DEFAULT '',
        detected_stack TEXT NOT NULL DEFAULT '',
        fallback INTEGER NOT NULL DEFAULT 0,
        fallback_reason TEXT,
        scope_type TEXT NOT NULL DEFAULT '',
        initial_failure_count INTEGER NOT NULL DEFAULT 0,
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        started_event_emitted_at TEXT,
        final_failure_count INTEGER,
        iterations INTEGER,
        result TEXT,
        failing_check_names TEXT NOT NULL DEFAULT '',
        unsupported_reason TEXT NOT NULL DEFAULT '',
        duplicate_terminal_finished_events INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_verify_sessions (
        session_id TEXT PRIMARY KEY,
        acceptance_criteria_count INTEGER NOT NULL DEFAULT 0,
        rollout_relevant INTEGER NOT NULL DEFAULT 0,
        spec_summary TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        started_event_emitted_at TEXT,
        feature_flag_audit_performed INTEGER,
        review_iterations INTEGER,
        audit_result TEXT,
        completion_status TEXT,
        gaps_found TEXT NOT NULL DEFAULT '',
        duplicate_terminal_finished_events INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_implement_sessions (
        session_id TEXT PRIMARY KEY,
        source TEXT NOT NULL DEFAULT 'production',
        issue_key_provided INTEGER NOT NULL DEFAULT 0,
        issue_key_type TEXT NOT NULL DEFAULT 'none',
        spec_input_types TEXT NOT NULL DEFAULT '',
        spec_word_count INTEGER NOT NULL DEFAULT 0,
        feature_size TEXT NOT NULL DEFAULT 'SMALL',
        feature_name TEXT NOT NULL DEFAULT '',
        rollout_needed INTEGER NOT NULL DEFAULT 0,
        acceptance_criteria_count INTEGER NOT NULL DEFAULT 0,
        open_questions_count INTEGER NOT NULL DEFAULT 0,
        spec_summary TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        started_event_emitted_at TEXT,
        completion_status TEXT NOT NULL DEFAULT '',
        plan_correction_count INTEGER,
        plan_task_count INTEGER,
        plan_phase_count INTEGER,
        feature_flag_used INTEGER,
        feature_flag_pattern TEXT,
        files_created INTEGER,
        files_modified INTEGER,
        tasks_completed INTEGER,
        review_iterations INTEGER,
        audit_result TEXT,
        audit_iterations INTEGER,
        validation_result TEXT,
        boundary_history_written INTEGER,
        boundary_history_value TEXT NOT NULL DEFAULT 'none',
        pr_created INTEGER,
        plan_deviation_notes TEXT NOT NULL DEFAULT '',
        child_steps_json TEXT NOT NULL DEFAULT '',
        duplicate_terminal_finished_events INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_task_runtime_sessions (
        session_id TEXT PRIMARY KEY,
        feature_size TEXT NOT NULL DEFAULT 'MEDIUM',
        issue_key TEXT NOT NULL DEFAULT '',
        feature_name TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        started_event_emitted_at TEXT,
        completion_status TEXT NOT NULL DEFAULT '',
        completed_phase_ids TEXT NOT NULL DEFAULT '',
        phase_outcomes TEXT NOT NULL DEFAULT '',
        last_incomplete_phase TEXT NOT NULL DEFAULT '',
        blocked_reason TEXT NOT NULL DEFAULT '',
        resolved_branch TEXT NOT NULL DEFAULT '',
        review_fix_iteration_count INTEGER NOT NULL DEFAULT 0,
        audit_gap_iteration_count INTEGER NOT NULL DEFAULT 0,
        audit_first_pass_convergence INTEGER NOT NULL DEFAULT 0,
        audit_recurring_gap_count INTEGER NOT NULL DEFAULT 0,
        audit_new_gap_count INTEGER NOT NULL DEFAULT 0,
        audit_attempted_repair_item_count INTEGER NOT NULL DEFAULT 0,
        audit_resolved_repair_item_count INTEGER NOT NULL DEFAULT 0,
        duplicate_terminal_finished_events INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_task_workflows (
        workflow_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL DEFAULT '',
        workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task' CHECK (workflow_name = 'bill-feature-task'),
        mode TEXT NOT NULL CHECK (mode IN ('prose', 'runtime')),
        implementation_skill TEXT NOT NULL DEFAULT '',
        contract_version TEXT NOT NULL,
        workflow_status TEXT NOT NULL DEFAULT 'pending',
        current_step_id TEXT NOT NULL DEFAULT '',
        steps_json TEXT NOT NULL DEFAULT '',
        artifacts_json TEXT NOT NULL DEFAULT '',
        issue_key TEXT,
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        state_entered_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        state_entered_at_estimated INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_task_execution_identities (
        workflow_id TEXT PRIMARY KEY,
        contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
        normalized_issue_key TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        governed_spec_path TEXT NOT NULL,
        mode TEXT NOT NULL CHECK (mode IN ('prose', 'runtime')),
        route_scope TEXT NOT NULL CHECK (route_scope IN ('standalone', 'goal_child')),
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_feature_task_identity_lookup
        ON feature_task_execution_identities(normalized_issue_key, repository_identity, route_scope)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_task_runtime_worker_leases (
        workflow_id TEXT PRIMARY KEY,
        contract_version TEXT NOT NULL,
        generation INTEGER NOT NULL CHECK (generation > 0),
        owner_token TEXT NOT NULL,
        host_identity TEXT NOT NULL,
        boot_identity TEXT NOT NULL,
        pid INTEGER NOT NULL CHECK (pid > 0),
        process_birth_token TEXT NOT NULL,
        lease_state TEXT NOT NULL CHECK (lease_state IN ('active', 'takeover_reserved')),
        heartbeat_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        phase_id TEXT NOT NULL,
        phase_attempt INTEGER NOT NULL CHECK (phase_attempt > 0),
        FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_verify_workflows (
        workflow_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL DEFAULT '',
        workflow_name TEXT NOT NULL DEFAULT 'bill-feature-verify',
        contract_version TEXT NOT NULL DEFAULT '${DbConstants.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION}',
        workflow_status TEXT NOT NULL DEFAULT 'pending',
        current_step_id TEXT NOT NULL DEFAULT '',
        steps_json TEXT NOT NULL DEFAULT '',
        artifacts_json TEXT NOT NULL DEFAULT '',
        issue_key TEXT,
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        state_entered_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        state_entered_at_estimated INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_run_sessions (
        workflow_id TEXT PRIMARY KEY,
        issue_key TEXT NOT NULL,
        feature_name TEXT NOT NULL DEFAULT '',
        subtask_total INTEGER NOT NULL DEFAULT 0,
        resumed INTEGER NOT NULL DEFAULT 0,
        started_at TEXT NOT NULL DEFAULT '',
        started_event_emitted_at TEXT,
        status TEXT,
        finished_at TEXT,
        finished_duration_ms INTEGER,
        subtasks_complete INTEGER,
        subtasks_blocked INTEGER,
        subtasks_skipped INTEGER,
        finished_event_emitted_at TEXT,
        mode TEXT NOT NULL DEFAULT 'runtime',
        stop_reason TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_subtask_events (
        issue_key TEXT NOT NULL,
        workflow_id TEXT NOT NULL,
        subtask_id INTEGER NOT NULL,
        subtask_name TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL,
        started_at TEXT NOT NULL,
        finished_at TEXT NOT NULL,
        duration_ms INTEGER NOT NULL,
        attempt_count INTEGER NOT NULL,
        blocked_reason TEXT,
        finalizing_agent_id TEXT,
        participating_agent_ids TEXT NOT NULL DEFAULT '[]',
        boundary_history_value TEXT NOT NULL DEFAULT 'none',
        boundary_history_written INTEGER NOT NULL DEFAULT 0,
        subtask_event_emitted_at TEXT,
        PRIMARY KEY (issue_key, subtask_id, workflow_id)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_issue_progress (
        parent_workflow_id TEXT NOT NULL,
        issue_key TEXT NOT NULL,
        total_invocations INTEGER NOT NULL DEFAULT 0,
        total_blocks INTEGER NOT NULL DEFAULT 0,
        total_resumes INTEGER NOT NULL DEFAULT 0,
        first_started_at TEXT,
        last_activity_at TEXT,
        last_blocked_at TEXT,
        latest_segment_workflow_id TEXT,
        last_blocked_segment_workflow_id TEXT,
        finished_at TEXT,
        status TEXT,
        state_entered_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        state_entered_at_estimated INTEGER NOT NULL DEFAULT 0,
        subtasks_complete INTEGER,
        subtasks_blocked INTEGER,
        subtasks_skipped INTEGER,
        mode TEXT NOT NULL DEFAULT 'runtime',
        finished_event_emitted_at TEXT,
        PRIMARY KEY (parent_workflow_id, issue_key)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_planning_preparations (
        parent_goal_workflow_id TEXT NOT NULL,
        normalized_issue_key TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
        governed_sub_spec_path TEXT NOT NULL,
        preparation_status TEXT NOT NULL CHECK (preparation_status IN ('pending', 'prepared')) DEFAULT 'prepared',
        contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
        parent_spec_hash TEXT NOT NULL,
        sub_spec_hash TEXT NOT NULL,
        decomposition_manifest_hash TEXT NOT NULL,
        phase_output_contract_id TEXT NOT NULL,
        phase_output_contract_version TEXT NOT NULL,
        preplan_payload_json TEXT NOT NULL,
        plan_payload_json TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (parent_goal_workflow_id, subtask_id)
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_goal_planning_preparations_lookup
        ON goal_planning_preparations(normalized_issue_key, repository_identity)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_shared_preplans (
        parent_goal_workflow_id TEXT PRIMARY KEY, normalized_issue_key TEXT NOT NULL,
        repository_identity TEXT NOT NULL, preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
        contract_version TEXT NOT NULL CHECK (contract_version = '0.2'), parent_spec_hash TEXT NOT NULL,
        decomposition_manifest_hash TEXT NOT NULL, planning_contract_id TEXT NOT NULL,
        planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
        phase_output_contract_id TEXT NOT NULL, phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
        payload_sha256 TEXT NOT NULL, preplan_payload_json TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(normalized_issue_key, repository_identity)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS goal_subtask_plans (
        parent_goal_workflow_id TEXT NOT NULL, normalized_issue_key TEXT NOT NULL, repository_identity TEXT NOT NULL,
        subtask_id INTEGER NOT NULL CHECK (subtask_id > 0), manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
        governed_sub_spec_path TEXT NOT NULL, sub_spec_hash TEXT NOT NULL,
        preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'), contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
        parent_spec_hash TEXT NOT NULL, decomposition_manifest_hash TEXT NOT NULL, planning_contract_id TEXT NOT NULL,
        planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'), phase_output_contract_id TEXT NOT NULL,
        phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'), payload_sha256 TEXT NOT NULL,
        plan_payload_json TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY(parent_goal_workflow_id, subtask_id), UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
        UNIQUE(parent_goal_workflow_id, manifest_order),
        FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered
        ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS telemetry_reconciliation_state (
        state_key TEXT PRIMARY KEY,
        last_completed_at TEXT NOT NULL
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_feature_task_workflows_updated
        ON feature_task_workflows(updated_at DESC)
      """.trimIndent(),
    )
}
