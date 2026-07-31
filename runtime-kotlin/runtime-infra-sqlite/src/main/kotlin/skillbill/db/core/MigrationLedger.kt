package skillbill.db.core

import java.sql.Connection

internal object MigrationLedger {
  // Version numbers are assigned per branch, so two lineages can ship different migrations under the
  // same number. A version-keyed ledger records whichever ran first and skips the other forever.
  // Identity is the name; the version column is retained as ordering metadata only.
  fun ensureNameKeyed(connection: Connection) {
    if (!tableExists(connection)) return
    if (!versionIsPrimaryKey(connection)) return

    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE schema_migrations RENAME TO schema_migrations_version_keyed")
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          name TEXT PRIMARY KEY,
          version INTEGER NOT NULL,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO schema_migrations (name, version, applied_at)
        SELECT name, version, applied_at FROM schema_migrations_version_keyed
        """.trimIndent(),
      )
      statement.execute("DROP TABLE schema_migrations_version_keyed")
    }
  }

  fun appliedNames(connection: Connection): Set<String> = connection.prepareStatement(
    """
      SELECT name
      FROM schema_migrations
      ORDER BY version
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

  fun record(connection: Connection, migration: DatabaseMigration) {
    connection.prepareStatement(
      """
      INSERT INTO schema_migrations (version, name)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setInt(1, migration.version)
      statement.setString(2, migration.name)
      statement.executeUpdate()
    }
  }

  private fun tableExists(connection: Connection): Boolean = connection.prepareStatement(
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
  ).use { statement ->
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }

  private fun versionIsPrimaryKey(connection: Connection): Boolean = connection.prepareStatement(
    "SELECT pk FROM pragma_table_info('schema_migrations') WHERE name = 'version'",
  ).use { statement ->
    statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getInt("pk") > 0 }
  }
}
