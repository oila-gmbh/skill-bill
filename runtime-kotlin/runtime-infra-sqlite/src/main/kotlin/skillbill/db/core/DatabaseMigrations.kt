package skillbill.db.core

import skillbill.db.telemetry.FeedbackEventMigration
import skillbill.db.telemetry.GoalTelemetryMigration
import java.sql.Connection
import java.sql.SQLException

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    listOf(
      DatabaseMigration(
        version = 1,
        name = "add-review-workflow-session-columns",
        operation = DatabaseColumnMigrations::apply,
      ),
      DatabaseMigration(
        version = 2,
        name = "normalize-feedback-event-outcomes",
        operation = FeedbackEventMigration::apply,
      ),
      DatabaseMigration(
        version = 3,
        name = "add-goal-telemetry-tables",
        operation = GoalTelemetryMigration::apply,
      ),
      DatabaseMigration(
        version = 4,
        name = "add-work-list-state-metadata",
        operation = DatabaseColumnMigrations::applyWorkListMetadata,
      ),
      DatabaseMigration(
        version = 5,
        name = "add-feature-task-execution-identities",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
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
            )
            statement.execute(
              """
              CREATE INDEX IF NOT EXISTS idx_feature_task_identity_lookup
                ON feature_task_execution_identities(normalized_issue_key, repository_identity, route_scope)
              """.trimIndent(),
            )
          }
        },
      ),
    ).also(::requireDeterministicMigrations)

  fun apply(connection: Connection) {
    connection.inImmediateTransaction {
      val appliedVersions = appliedMigrationVersions(this)
      migrations
        .filterNot { migration -> migration.version in appliedVersions }
        .forEach { migration ->
          migration.apply(this)
          recordMigration(migration)
        }
    }
  }

  private fun appliedMigrationVersions(connection: Connection): Set<Int> = connection.prepareStatement(
    """
      SELECT version
      FROM schema_migrations
      ORDER BY version
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getInt("version"))
        }
      }
    }
  }

  private fun Connection.recordMigration(migration: DatabaseMigration) {
    prepareStatement(
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

  private fun requireDeterministicMigrations(migrations: List<DatabaseMigration>) {
    val versions = migrations.map { migration -> migration.version }
    val names = migrations.map { migration -> migration.name }

    require(versions == versions.sorted()) { "Database migrations must be ordered by version." }
    require(versions.toSet().size == versions.size) { "Database migration versions must be unique." }
    require(names.toSet().size == names.size) { "Database migration names must be unique." }
  }
}

internal class DatabaseMigration(
  val version: Int,
  val name: String,
  private val operation: (Connection) -> Unit,
) {
  fun apply(connection: Connection) {
    operation(connection)
  }
}

internal inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
  val previousAutoCommit = autoCommit
  autoCommit = false
  return try {
    val result = block()
    commit()
    result
  } catch (error: SQLException) {
    rollback()
    throw error
  } catch (error: IllegalArgumentException) {
    rollback()
    throw error
  } finally {
    autoCommit = previousAutoCommit
  }
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> Connection.inImmediateTransaction(block: Connection.() -> T): T {
  createStatement().use { it.execute("BEGIN IMMEDIATE") }
  return try {
    val result = block()
    createStatement().use { it.execute("COMMIT") }
    result
  } catch (error: Exception) {
    rollbackImmediateTransaction()
    throw error
  }
}

private fun Connection.rollbackImmediateTransaction() {
  runCatching { createStatement().use { it.execute("ROLLBACK") } }
}
