package skillbill.db.core

internal val databaseSchemaStatementsEarly: List<String> =
  listOf(

    """
      CREATE TABLE IF NOT EXISTS producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt, repair_turn, agent_id)
      )
    """.trimIndent(),
    """
      CREATE TABLE IF NOT EXISTS rejected_output_diagnostics (
        identity TEXT PRIMARY KEY,
        workflow_id TEXT NOT NULL,
        phase_id TEXT NOT NULL,
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
        rule TEXT NOT NULL,
        rejection_path TEXT NOT NULL,
        reason TEXT NOT NULL,
        agent_id TEXT NOT NULL,
        model TEXT NOT NULL,
        recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
        sha256 TEXT NOT NULL,
        lifecycle TEXT NOT NULL CHECK (lifecycle IN ('stored', 'oversized', 'expired')),
        payload BLOB,
        UNIQUE (workflow_id, phase_id, attempt, repair_turn),
        CHECK (
          (lifecycle = 'stored' AND payload IS NOT NULL) OR
          (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
        )
      )
    """.trimIndent(),
    """
      CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostics_selector
        ON rejected_output_diagnostics(workflow_id, phase_id, attempt, repair_turn)
    """.trimIndent(),
    """
      CREATE TABLE IF NOT EXISTS schema_migrations (
        name TEXT PRIMARY KEY,
        version INTEGER NOT NULL,
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
        routed_skill_canonical TEXT NOT NULL DEFAULT 'unresolved',
        detected_stack_canonical TEXT NOT NULL DEFAULT 'unresolved',
        detected_scope_canonical TEXT NOT NULL DEFAULT 'unresolved',
        detected_scope_detail TEXT,
        source_path TEXT,
        raw_text TEXT NOT NULL,
        review_finished_at TEXT,
        review_finished_event_emitted_at TEXT,
        imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
    """.trimIndent(),
    """
      CREATE TABLE IF NOT EXISTS review_accounting (
        review_id TEXT PRIMARY KEY,
        packet_digest TEXT NOT NULL,
        bounded_payload_json TEXT NOT NULL,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
      CREATE TABLE IF NOT EXISTS unaddressed_findings (
        issue_key TEXT NOT NULL,
        workflow_id TEXT NOT NULL,
        subtask_id INTEGER NOT NULL,
        review_pass_number INTEGER NOT NULL,
        finding_ordinal INTEGER NOT NULL,
        severity TEXT NOT NULL,
        issue_category TEXT NOT NULL DEFAULT 'other',
        location TEXT NOT NULL,
        summary TEXT NOT NULL,
        recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        review_run_id TEXT,
        finding_id TEXT,
        claim_verdict TEXT,
        scope_disposition TEXT,
        citations TEXT NOT NULL DEFAULT '',
        severity_adjustment_direction TEXT,
        severity_adjustment_justification TEXT,
        PRIMARY KEY (workflow_id, review_pass_number, finding_ordinal)
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
        -- NULL means healthy: no delivery has failed. Non-null is a real delivery failure. The
        -- legacy shape was NOT NULL DEFAULT '', which made "no error" and "error" indistinguishable
        -- from the column type alone; relax-telemetry-outbox-last-error backfills '' to NULL.
        last_error TEXT,
        -- Nullable with no default: a row enqueued before release attribution existed has no
        -- version to report, and NULL keeps it distinguishable from a genuine recorded version.
        skill_bill_version TEXT
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
  )
