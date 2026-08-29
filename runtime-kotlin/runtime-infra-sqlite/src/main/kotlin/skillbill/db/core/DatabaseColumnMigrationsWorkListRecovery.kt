package skillbill.db.core

import java.sql.Connection

internal object DatabaseColumnMigrationsWorkListRecovery {
  fun recoverWorkListIssueKeys(connection: Connection) {
    recoverRuntimeWorkflowIssueKeys(connection)
    recoverGoalContinuationWorkflowIssueKeys(connection)
    recoverDecompositionWorkflowIssueKeys(connection)
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

  fun ensureReconciliationIndexes(connection: Connection) {
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
