package skillbill.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StaleSessionReconcilerTest {
  @Test
  fun `stale feature_implement session beyond threshold is closed with status stale`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('fis-20260101-120000-aa11', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleFeatureImplementSessions(connection, thresholdSeconds = 28_800L)

      assertEquals(1, reconciled)
      connection.createStatement().use { statement ->
        val sessionId = "fis-20260101-120000-aa11"
        statement.executeQuery(
          "SELECT completion_status, finished_at FROM feature_implement_sessions WHERE session_id = '$sessionId'",
        ).use { resultSet ->
          resultSet.next()
          assertEquals("stale", resultSet.getString("completion_status"))
          assert(resultSet.getString("finished_at") != null) { "finished_at must be set after reconciliation" }
        }
      }
    }
  }

  @Test
  fun `stale feature_task_runtime session beyond threshold is closed with status stale`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test-ftr").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('ftr-20260101-120000-bb22', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleFeatureTaskRuntimeSessions(connection, thresholdSeconds = 28_800L)

      assertEquals(1, reconciled)
      connection.createStatement().use { statement ->
        val sessionId = "ftr-20260101-120000-bb22"
        statement.executeQuery(
          "SELECT completion_status, finished_at FROM feature_task_runtime_sessions WHERE session_id = '$sessionId'",
        ).use { resultSet ->
          resultSet.next()
          assertEquals("stale", resultSet.getString("completion_status"))
          assert(resultSet.getString("finished_at") != null)
        }
      }
    }
  }

  @Test
  fun `session within threshold is not reconciled`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test-within").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('fis-20260101-120000-cc33', datetime('now', '-1000 seconds'), NULL, '')
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleFeatureImplementSessions(connection, thresholdSeconds = 28_800L)

      assertEquals(0, reconciled)
    }
  }

  @Test
  fun `already finished session is not touched by reconciliation`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test-finished").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('fis-20260101-120000-dd44', datetime('now', '-40000 seconds'), datetime('now', '-1000 seconds'), 'completed')
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleFeatureImplementSessions(connection, thresholdSeconds = 28_800L)

      assertEquals(0, reconciled)
    }
  }
}
