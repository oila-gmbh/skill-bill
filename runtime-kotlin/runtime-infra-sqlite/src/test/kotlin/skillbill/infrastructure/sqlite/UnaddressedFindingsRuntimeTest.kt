package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.goalrunner.model.UnaddressedFinding
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnaddressedFindingsRuntimeTest {
  @Test
  fun `goal existence comes from durable workflows without telemetry rows`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-goal-authority").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows " +
          "(workflow_id, workflow_name, mode, contract_version, issue_key) VALUES (?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "goal-parent-1")
        statement.setString(2, "bill-feature-task")
        statement.setString(3, "runtime")
        statement.setString(4, "0.2")
        statement.setString(5, "SKILL-135")
        statement.executeUpdate()
      }
      val repository = SQLiteUnaddressedFindingsRepository(connection)

      assertTrue(repository.issueExists("SKILL-135"))
      assertFalse(repository.issueExists("SKILL-404"))
    }
  }

  @Test
  fun `re-recording a review pass replaces its ledger rows`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val first = finding(1, "minor", "src/First.kt:7")
      val replacement = finding(1, "major", "src/Second.kt:9")

      repository.replaceLedgerForPass("workflow-1", 1, listOf(first))
      repository.replaceLedgerForPass("workflow-1", 1, listOf(replacement))

      assertEquals(listOf(replacement), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `recording a later review pass removes remediated findings from earlier passes`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-later-pass").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val deferred = finding(2, "minor", "src/Deferred.kt:8")
      repository.replaceLedgerForPass("workflow-1", 1, listOf(finding(1, "blocker", "src/First.kt:7"), deferred))
      val remaining = finding(1, "minor", "src/Second.kt:9").copy(reviewPassNumber = 2)

      repository.replaceLedgerForPass("workflow-1", 2, listOf(remaining))

      assertEquals(listOf(deferred, remaining), repository.fetchLedger("SKILL-135"))
    }
  }

  private fun finding(ordinal: Int, severity: String, location: String) = UnaddressedFinding(
    issueKey = "SKILL-135",
    subtaskId = 3,
    workflowId = "workflow-1",
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = severity,
    issueCategory = "data_persistence",
    location = location,
    summary = "Finding $ordinal",
  )
}
