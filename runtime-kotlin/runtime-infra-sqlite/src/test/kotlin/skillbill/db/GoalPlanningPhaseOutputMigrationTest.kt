package skillbill.db

import skillbill.db.core.DatabaseMigrations
import skillbill.db.core.DatabaseRuntime
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPhaseOutputMigrationTest {
  @Test
  fun `forward migration preserves compatible goal planning rows and tightens both tables`() {
    val dbPath = Files.createTempDirectory("goal-planning-compatible-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedPlanningRow(connection, phaseOutputContractVersion = "0.2")
      connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version = 11") }

      DatabaseMigrations.apply(connection)

      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 11"))
      assertFailsWith<java.sql.SQLException> {
        seedPlanningRow(connection, phaseOutputContractVersion = "0.1", workflowId = "wfl-incompatible")
      }
    }
  }

  @Test
  fun `forward migration rejects incompatible provenance before changing schema or rows`() {
    val dbPath = Files.createTempDirectory("goal-planning-incompatible-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = ON") }
      seedPlanningRow(connection, phaseOutputContractVersion = "0.1")
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = OFF") }
      connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version = 11") }
      val sharedSchema = tableSql(connection, "goal_shared_preplans")
      val subtaskSchema = tableSql(connection, "goal_subtask_plans")

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        DatabaseMigrations.apply(connection)
      }

      assertEquals(sharedSchema, tableSql(connection, "goal_shared_preplans"))
      assertEquals(subtaskSchema, tableSql(connection, "goal_subtask_plans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 11"))
    }
  }

  private fun seedPlanningRow(
    connection: Connection,
    phaseOutputContractVersion: String,
    workflowId: String = "wfl-planning",
  ) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        INSERT INTO goal_shared_preplans VALUES (
          '$workflowId', 'SKILL-000-$workflowId', 'repo', 'prepared', '0.2', 'spec-hash', 'manifest-hash',
          'goal-planning-preparation', '0.2', 'feature-task-runtime-phase-output', '$phaseOutputContractVersion',
          'payload-sha', '{}', CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO goal_subtask_plans VALUES (
          '$workflowId', 'SKILL-000-$workflowId', 'repo', 1, 0, '.feature-specs/x/spec_subtask_1.md', 'sub-hash',
          'prepared', '0.2', 'spec-hash', 'manifest-hash', 'goal-planning-preparation', '0.2',
          'feature-task-runtime-phase-output', '$phaseOutputContractVersion', 'payload-sha', '{}', CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
  }

  private fun scalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { rows ->
      rows.next()
      rows.getInt(1)
    }
  }

  private fun tableSql(connection: Connection, table: String): String = connection.prepareStatement(
    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
  ).use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { rows ->
      rows.next()
      rows.getString(1)
    }
  }
}
