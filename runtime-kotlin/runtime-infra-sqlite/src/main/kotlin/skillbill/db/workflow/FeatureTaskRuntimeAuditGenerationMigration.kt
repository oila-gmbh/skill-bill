package skillbill.db.workflow

import java.sql.Connection

/**
 * Append-only audit-generation history. There is deliberately no `updated_at` and no uniqueness escape
 * hatch: the primary key is (workflow_id, generation_ordinal) and the store issues only INSERT, so an
 * accepted generation's gap text, repair results, recurrence, and decision checkpoint cannot be rewritten
 * by a later plan. The replaceable audit-repair-state artifact remains a derived cache of these rows.
 */
internal object FeatureTaskRuntimeAuditGenerationMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS feature_task_runtime_audit_generations (
          workflow_id TEXT NOT NULL,
          generation_ordinal INTEGER NOT NULL CHECK (generation_ordinal >= 1),
          repository_checkpoint TEXT NOT NULL,
          contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
          generation_json TEXT NOT NULL,
          recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (workflow_id, generation_ordinal)
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_feature_task_runtime_audit_generations_workflow
          ON feature_task_runtime_audit_generations(workflow_id, generation_ordinal)
        """.trimIndent(),
      )
    }
  }
}
