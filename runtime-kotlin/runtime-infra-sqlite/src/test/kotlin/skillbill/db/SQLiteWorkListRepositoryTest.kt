package skillbill.db

import skillbill.db.core.DatabaseRuntime
import skillbill.db.worklist.SQLiteWorkListRepository
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class SQLiteWorkListRepositoryTest {
  @Test
  fun `work list sorts parsed instants exactly before applying its limit and excludes child goal rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-work-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertFeatureTask(connection, "wfl-old", "prose", "2026-05-01T12:00:00.000001Z")
      insertFeatureVerify(connection, "wfv-mid", "2026-05-01T12:00:00.000002+00:00")
      insertGoal(connection, "goal-new", "SKILL-117", "2026-05-01 12:00:00.000003")
      insertFeatureTask(connection, "wfl-tie-a", "runtime", "2026-05-01T12:00:00.000004Z")
      insertFeatureVerify(connection, "wfv-tie-z", "2026-05-01T12:00:00.000004Z")
      insertGoalChildRows(connection)

      val repository = SQLiteWorkListRepository(connection)
      val all = repository.list()

      assertEquals(
        listOf("wfv-tie-z", "wfl-tie-a", "goal-new", "wfv-mid", "wfl-old"),
        all.map { it.workflowId },
      )
      assertEquals(
        listOf("feature-verify", "feature-task-runtime", "feature-goal", "feature-verify", "feature-task-prose"),
        all.map { it.workflowKind.wireValue },
      )
      assertEquals(listOf("wfv-tie-z", "wfl-tie-a", "goal-new"), repository.list(limit = 3).map { it.workflowId })
    }
  }

  private fun insertFeatureTask(connection: Connection, workflowId: String, mode: String, startedAt: String) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_workflows (
        workflow_id, mode, contract_version, workflow_status, issue_key, started_at, state_entered_at
      ) VALUES (?, ?, '1.0.0', 'running', 'SKILL-117', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, mode)
      statement.setString(3, startedAt)
      statement.setString(4, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertFeatureVerify(connection: Connection, workflowId: String, startedAt: String) {
    connection.prepareStatement(
      """
      INSERT INTO feature_verify_workflows (
        workflow_id, contract_version, workflow_status, issue_key, started_at, state_entered_at
      ) VALUES (?, '1.0.0', 'running', 'SKILL-117', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, startedAt)
      statement.setString(3, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertGoal(connection: Connection, workflowId: String, issueKey: String, startedAt: String) {
    connection.prepareStatement(
      """
      INSERT INTO goal_issue_progress (
        parent_workflow_id, issue_key, first_started_at, status, state_entered_at
      ) VALUES (?, ?, ?, 'running', ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, issueKey)
      statement.setString(3, startedAt)
      statement.setString(4, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertGoalChildRows(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at, mode)
        VALUES ('goal-child-session', 'SKILL-117', '2026-05-02T12:00:00Z', 'runtime')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO goal_subtask_events (
          issue_key, workflow_id, subtask_id, status, started_at, finished_at, duration_ms, attempt_count
        ) VALUES ('SKILL-117', 'goal-child-session', 1, 'complete', '2026-05-02T12:00:00Z',
                  '2026-05-02T12:01:00Z', 60000, 1)
        """.trimIndent(),
      )
    }
  }
}
