package skillbill.db

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
      "feature_implement_workflows",
      "feature_verify_workflows",
    )

  val indexNames: Set<String> =
    setOf(
      "idx_feedback_events_run",
      "idx_learnings_scope",
      "idx_telemetry_outbox_pending",
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
        scope_type TEXT NOT NULL DEFAULT '',
        initial_failure_count INTEGER NOT NULL DEFAULT 0,
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        started_event_emitted_at TEXT,
        final_failure_count INTEGER,
        iterations INTEGER,
        result TEXT,
        failing_check_names TEXT NOT NULL DEFAULT '',
        unsupported_reason TEXT NOT NULL DEFAULT '',
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
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_implement_sessions (
        session_id TEXT PRIMARY KEY,
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
        finished_at TEXT,
        finished_event_emitted_at TEXT
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS feature_implement_workflows (
        workflow_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL DEFAULT '',
        workflow_name TEXT NOT NULL DEFAULT 'bill-feature-implement',
        contract_version TEXT NOT NULL DEFAULT '${DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION}',
        workflow_status TEXT NOT NULL DEFAULT 'pending',
        current_step_id TEXT NOT NULL DEFAULT '',
        steps_json TEXT NOT NULL DEFAULT '',
        artifacts_json TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        finished_at TEXT
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
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        finished_at TEXT
      )
      """.trimIndent(),
    )
}
