package skillbill.db

import skillbill.db.core.DatabaseMigrations
import skillbill.db.core.DatabaseRuntime
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPhaseOutputMigrationTest {
  @Test
  fun `phase-output 0_2 migration discards goal planning rows recorded under 0_1`() {
    val dbPath = Files.createTempDirectory("goal-planning-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = ON") }
      seedLegacyPlanningRow(connection)
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = OFF") }
      connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version = 10") }

      DatabaseMigrations.apply(connection)

      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
      assertEquals(
        1,
        scalar(
          connection,
          "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_goal_subtask_plans_ordered'",
        ),
      )
      assertFailsWith<java.sql.SQLException> {
        seedLegacyPlanningRow(connection)
      }
    }
  }

  private fun seedLegacyPlanningRow(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute("DELETE FROM goal_subtask_plans")
      statement.execute("DELETE FROM goal_shared_preplans")
      statement.execute(
        """
        INSERT INTO goal_shared_preplans VALUES (
          'wfl-legacy', 'SKILL-000', 'repo', 'prepared', '0.2', 'spec-hash', 'manifest-hash',
          'goal-planning-preparation', '0.2', 'feature-task-runtime-phase-output', '0.1',
          'payload-sha', '{}', CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO goal_subtask_plans VALUES (
          'wfl-legacy', 'SKILL-000', 'repo', 1, 0, '.feature-specs/x/spec_subtask_1.md', 'sub-hash',
          'prepared', '0.2', 'spec-hash', 'manifest-hash', 'goal-planning-preparation', '0.2',
          'feature-task-runtime-phase-output', '0.1', 'payload-sha', '{}', CURRENT_TIMESTAMP
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
}
