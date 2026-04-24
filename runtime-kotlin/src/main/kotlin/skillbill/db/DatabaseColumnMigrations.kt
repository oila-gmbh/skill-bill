package skillbill.db

import java.sql.Connection

internal object DatabaseColumnMigrations {
  private val safeIdentifierPattern = Regex("^[a-z_][a-z0-9_]*$")

  fun apply(connection: Connection) {
    ensureFeatureVerifyWorkflowColumns(connection)
    ensureReviewRunColumns(connection)
    backfillReviewSessionIds(connection)
    ensureFeatureImplementSessionColumns(connection)
    ensureFeatureVerifySessionColumns(connection)
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

  private fun ensureReviewRunColumns(connection: Connection) {
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
  }

  private fun ensureFeatureVerifySessionColumns(connection: Connection) {
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
  }

  private fun ensureColumn(connection: Connection, tableName: String, columnName: String, definition: String) {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    if (tableColumnNames(connection = connection, tableName = tableName).contains(columnName)) {
      return
    }
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
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
