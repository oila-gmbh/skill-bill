package skillbill.db.core

import skillbill.db.telemetry.FeedbackEventMigration
import skillbill.db.telemetry.GoalTelemetryMigration
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
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
      DatabaseMigration(
        version = 9,
        name = "normalize-goal-planning-preparations",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(normalized_issue_key, repository_identity)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
                "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
            )
          }
        },
      ),
      DatabaseMigration(
        version = 10,
        name = "rebuild-goal-planning-plans-for-phase-output-0-2",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE goal_subtask_plans RENAME TO goal_subtask_plans_pre_0_2")
            statement.execute("ALTER TABLE goal_shared_preplans RENAME TO goal_shared_preplans_pre_0_2")
            statement.execute(
              """
              CREATE TABLE goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.1', '0.2')),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(normalized_issue_key, repository_identity)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE TABLE goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.1', '0.2')),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            // Carry existing planning rows across the widened constraint. Their recorded
            // phase-output provenance stays truthful; the read seam decides whether a legacy
            // stamp is still usable, so discarding in-flight goal planning is never automatic.
            statement.execute(
              "INSERT INTO goal_shared_preplans SELECT * FROM goal_shared_preplans_pre_0_2",
            )
            statement.execute(
              "INSERT INTO goal_subtask_plans SELECT * FROM goal_subtask_plans_pre_0_2",
            )
            // Drop before recreating the index: SQLite carries an index across a table rename, so
            // the renamed table still owns the old index name until it is gone.
            statement.execute("DROP TABLE goal_subtask_plans_pre_0_2")
            statement.execute("DROP TABLE goal_shared_preplans_pre_0_2")
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
                "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
            )
          }
        },
      ),
      DatabaseMigration(
        version = 11,
        name = "require-goal-planning-phase-output-0-2",
        operation = { connection ->
          connection.createStatement().use { statement ->
            val incompatibleTable = listOf("goal_shared_preplans", "goal_subtask_plans").firstOrNull { table ->
              statement.executeQuery(
                "SELECT 1 FROM $table WHERE phase_output_contract_version != '0.2' LIMIT 1",
              ).use { rows -> rows.next() }
            }
            if (incompatibleTable != null) {
              throw InvalidGoalPlanningPreparationSchemaError(
                sourceLabel = incompatibleTable,
                fieldPath = "phase_output_contract_version",
                reason = "migration requires compatible phase-output provenance '0.2'",
              )
            }

            statement.execute("ALTER TABLE goal_subtask_plans RENAME TO goal_subtask_plans_pre_strict_0_2")
            statement.execute("ALTER TABLE goal_shared_preplans RENAME TO goal_shared_preplans_pre_strict_0_2")
            statement.execute(
              """
              CREATE TABLE goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.2'),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(normalized_issue_key, repository_identity)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE TABLE goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.2'),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM goal_shared_preplans_pre_strict_0_2")
            statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM goal_subtask_plans_pre_strict_0_2")
            statement.execute("DROP TABLE goal_subtask_plans_pre_strict_0_2")
            statement.execute("DROP TABLE goal_shared_preplans_pre_strict_0_2")
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
                "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
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
