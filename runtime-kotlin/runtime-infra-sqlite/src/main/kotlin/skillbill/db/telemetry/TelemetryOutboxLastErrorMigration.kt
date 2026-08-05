package skillbill.db.telemetry

import java.sql.Connection

/**
 * Relaxes `telemetry_outbox.last_error` from `TEXT NOT NULL DEFAULT ''` to a nullable `TEXT`, so
 * NULL means "healthy, nothing has failed" and a non-null value is always a real delivery failure.
 * The legacy shape wrote `''` on both the success path and on enqueue, which made the healthy and
 * failed states indistinguishable from the column alone.
 *
 * SQLite cannot drop a NOT NULL constraint in place, so the table is rebuilt: every existing row is
 * carried across verbatim except `last_error = ''`, which becomes NULL. No row is ever deleted.
 */
internal object TelemetryOutboxLastErrorMigration {
  fun apply(connection: Connection) {
    if (!needsMigration(connection)) return

    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE telemetry_outbox RENAME TO telemetry_outbox_legacy")
      statement.execute(
        """
        CREATE TABLE telemetry_outbox (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          event_name TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          synced_at TEXT,
          last_error TEXT
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO telemetry_outbox (id, event_name, payload_json, created_at, synced_at, last_error)
        SELECT id, event_name, payload_json, created_at, synced_at,
               CASE WHEN last_error = '' THEN NULL ELSE last_error END
        FROM telemetry_outbox_legacy
        """.trimIndent(),
      )
      statement.execute("DROP TABLE telemetry_outbox_legacy")
      statement.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_telemetry_outbox_pending
          ON telemetry_outbox(synced_at, id)
        """.trimIndent(),
      )
    }
  }

  // The ledger already gates this to one application, but the shape check keeps a re-run — from a
  // rebuilt ledger or a hand-repaired store — a no-op rather than a second destructive rebuild.
  private fun needsMigration(connection: Connection): Boolean = connection.prepareStatement(
    "SELECT \"notnull\" FROM pragma_table_info('telemetry_outbox') WHERE name = 'last_error'",
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next() && resultSet.getInt("notnull") != 0
    }
  }
}
