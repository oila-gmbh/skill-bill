package skillbill.infrastructure.sqlite.workflow

import skillbill.infrastructure.sqlite.core.DatabaseColumnMigrations
import java.sql.Connection

internal object AuditRepairCycleMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS audit_repair_cycles (
          workflow_id TEXT NOT NULL,
          cycle_id TEXT NOT NULL,
          audit_attempt INTEGER NOT NULL CHECK (audit_attempt > 0),
          revision INTEGER NOT NULL CHECK (revision >= 0),
          stage TEXT NOT NULL,
          PRIMARY KEY (workflow_id, cycle_id),
          UNIQUE (workflow_id, audit_attempt)
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS audit_repair_cycle_revisions (
          workflow_id TEXT NOT NULL,
          cycle_id TEXT NOT NULL,
          revision INTEGER NOT NULL CHECK (revision >= 0),
          evidence_json TEXT NOT NULL,
          PRIMARY KEY (workflow_id, cycle_id, revision),
          FOREIGN KEY (workflow_id, cycle_id) REFERENCES audit_repair_cycles(workflow_id, cycle_id)
        )
        """.trimIndent(),
      )
    }
  }
}

internal object AuditRepairLaunchBindingMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS audit_repair_launch_bindings (
          workflow_id TEXT NOT NULL,
          audit_attempt INTEGER NOT NULL CHECK (audit_attempt > 0),
          cycle_id TEXT NOT NULL,
          execution_id TEXT NOT NULL,
          requested_session_id TEXT NOT NULL,
          provider_session_id TEXT,
          owner_token TEXT NOT NULL,
          fencing_generation INTEGER NOT NULL CHECK (fencing_generation > 0),
          bound_at TEXT NOT NULL,
          PRIMARY KEY (workflow_id, audit_attempt, cycle_id),
          UNIQUE (workflow_id, execution_id, requested_session_id)
        )
        """.trimIndent(),
      )
    }
    DatabaseColumnMigrations.ensureColumn(connection, "audit_repair_launch_bindings", "checkpoint_json", "TEXT")
  }
}
