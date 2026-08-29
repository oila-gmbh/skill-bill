package skillbill.db.core

import java.sql.Connection

internal fun ensureFeatureTaskRuntimeSessionLifecycleColumns(connection: Connection) {
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "started_at",
    "TEXT NOT NULL DEFAULT ''",
  )
  DatabaseColumnMigrationsEnsure.backfillBlankColumn(
    connection,
    "feature_task_runtime_sessions",
    "started_at",
    "CURRENT_TIMESTAMP",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "started_event_emitted_at",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "finished_at",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "finished_event_emitted_at",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "review_fix_iteration_count",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
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
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "feature_task_runtime_sessions",
      column,
      "INTEGER NOT NULL DEFAULT 0",
    )
  }
}

internal fun ensureFeatureTaskRuntimeSessionMetricColumns(connection: Connection) {
  ensureFeatureTaskRuntimeSessionRegenColumns(connection)
  ensureFeatureTaskRuntimeSessionVerificationColumns(connection)
}

private fun ensureFeatureTaskRuntimeSessionRegenColumns(connection: Connection) {
  listOf(
    "regeneration_activation_count",
    "regeneration_attempt_count",
  ).forEach { column ->
    DatabaseColumnMigrationsEnsure.ensureColumn(
      connection,
      "feature_task_runtime_sessions",
      column,
      "INTEGER NOT NULL DEFAULT 0",
    )
  }
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "regeneration_outcome_counts_json",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "crash_reconciliation_count",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "crash_reconciliation_reason_counts_json",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "estimated_phase_tokens_json",
    "TEXT",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection,
    "feature_task_runtime_sessions",
    "estimated_total_tokens",
    "INTEGER",
  )
}

private fun ensureFeatureTaskRuntimeSessionVerificationColumns(connection: Connection) {
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "finding_verification_verified_count",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "finding_verification_rejected_count",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "review_fix_cap_exhausted",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
  DatabaseColumnMigrationsEnsure.ensureColumn(
    connection = connection,
    tableName = "feature_task_runtime_sessions",
    columnName = "duplicate_terminal_finished_events",
    definition = "INTEGER NOT NULL DEFAULT 0",
  )
}
