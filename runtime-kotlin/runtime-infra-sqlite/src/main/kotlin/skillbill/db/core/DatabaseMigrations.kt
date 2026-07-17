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
        name = "recover-work-list-issue-keys",
        operation = DatabaseColumnMigrations::recoverWorkListIssueKeys,
      ),
      DatabaseMigration(
        version = 6,
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
      DatabaseMigration(
        version = 7,
        name = "add-feature-task-runtime-worker-leases",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS feature_task_runtime_worker_leases (
                workflow_id TEXT PRIMARY KEY,
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                generation INTEGER NOT NULL CHECK (generation > 0),
                owner_token TEXT NOT NULL,
                host_identity TEXT NOT NULL,
                boot_identity TEXT NOT NULL,
                pid INTEGER NOT NULL CHECK (pid > 0),
                process_birth_token TEXT NOT NULL,
                lease_state TEXT NOT NULL CHECK (lease_state IN ('active', 'takeover_reserved')),
                heartbeat_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                phase_id TEXT NOT NULL,
                phase_attempt INTEGER NOT NULL CHECK (phase_attempt > 0),
                FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 8,
        name = "add-goal-planning-preparations",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_planning_preparations (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                governed_sub_spec_path TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status IN ('pending', 'prepared')) DEFAULT 'prepared',
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                parent_spec_hash TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (parent_goal_workflow_id, subtask_id)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE INDEX IF NOT EXISTS idx_goal_planning_preparations_lookup
                ON goal_planning_preparations(normalized_issue_key, repository_identity)
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
