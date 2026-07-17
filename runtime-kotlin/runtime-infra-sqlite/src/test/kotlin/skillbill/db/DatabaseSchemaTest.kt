package skillbill.db

import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DatabaseSchema
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseSchemaTest {
  @Test
  fun `ensureDatabase creates parent directories enables foreign keys and bootstraps schema`() {
    val tempDir = Files.createTempDirectory("runtime-kotlin-db-schema")
    val dbPath = tempDir.resolve("nested").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(Files.isDirectory(dbPath.parent))
      assertTrue(Files.exists(dbPath))
      assertPragmas(connection)
      assertSchemaObjects(connection)
      assertGoalIssueProgressSchema(connection)
    }
  }

  @Test
  fun `ensureDatabase creates the goal planning preparations table and lookup index`() {
    val tempDir = Files.createTempDirectory("runtime-kotlin-db-schema-goal-planning")
    val dbPath = tempDir.resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = sqliteObjects(connection = connection, type = "table")
      val indexes = sqliteObjects(connection = connection, type = "index")

      assertTrue("goal_planning_preparations" in DatabaseSchema.tableNames)
      assertTrue("goal_planning_preparations" in tables)
      assertTrue("idx_goal_planning_preparations_lookup" in DatabaseSchema.indexNames)
      assertTrue("idx_goal_planning_preparations_lookup" in indexes)
    }
  }

  private fun assertPragmas(connection: Connection) {
    assertEquals(1, pragmaInt(connection, "foreign_keys"))
    assertEquals(5000, pragmaInt(connection, "busy_timeout"))
    assertEquals("wal", pragmaString(connection, "journal_mode").lowercase())
  }

  private fun assertSchemaObjects(connection: Connection) {
    val tables = sqliteObjects(connection = connection, type = "table")
    val indexes = sqliteObjects(connection = connection, type = "index")
    assertTrue(
      DatabaseSchema.tableNames.all { it in tables },
      "Missing tables: ${DatabaseSchema.tableNames - tables}",
    )
    assertTrue(
      DatabaseSchema.indexNames.all { it in indexes },
      "Missing indexes: ${DatabaseSchema.indexNames - indexes}",
    )
    assertTrue(
      "feature_task_workflows" in DatabaseSchema.tableNames,
      "feature_task_workflows must be registered in DatabaseSchema.tableNames.",
    )
    assertTrue(
      "feature_task_workflows" in tables,
      "feature_task_workflows must be created by the base schema.",
    )
    assertTrue(
      "feature_implement_workflows" !in tables && "feature_task_runtime_workflows" !in tables,
      "old feature-task workflow tables must not be authoritative stores in fresh schema.",
    )
  }

  private fun assertGoalIssueProgressSchema(connection: Connection) {
    val goalIssueColumns = tableInfo(connection, "goal_issue_progress")
    assertEquals(
      listOf(
        "parent_workflow_id",
        "issue_key",
        "total_invocations",
        "total_blocks",
        "total_resumes",
        "first_started_at",
        "last_activity_at",
        "last_blocked_at",
        "latest_segment_workflow_id",
        "last_blocked_segment_workflow_id",
        "finished_at",
        "status",
        "state_entered_at",
        "state_entered_at_estimated",
        "subtasks_complete",
        "subtasks_blocked",
        "subtasks_skipped",
        "mode",
        "finished_event_emitted_at",
      ),
      goalIssueColumns.map { it.name },
    )
    assertEquals(1, goalIssueColumns.single { it.name == "parent_workflow_id" }.primaryKeyPosition)
    assertEquals(2, goalIssueColumns.single { it.name == "issue_key" }.primaryKeyPosition)
  }

  private fun pragmaInt(connection: Connection, name: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA $name").use { resultSet ->
      resultSet.next()
      resultSet.getInt(1)
    }
  }

  private fun pragmaString(connection: Connection, name: String): String =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA $name").use { resultSet ->
        resultSet.next()
        resultSet.getString(1)
      }
    }

  private fun sqliteObjects(connection: Connection, type: String): Set<String> = connection.prepareStatement(
    """
      SELECT name
      FROM sqlite_master
      WHERE type = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, type)
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

  private fun tableInfo(connection: Connection, tableName: String): List<TableColumn> =
    connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(
              TableColumn(
                name = resultSet.getString("name"),
                primaryKeyPosition = resultSet.getInt("pk"),
              ),
            )
          }
        }
      }
    }

  private data class TableColumn(
    val name: String,
    val primaryKeyPosition: Int,
  )
}
