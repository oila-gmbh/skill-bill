package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.goalrunner.model.UnaddressedFinding
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UnaddressedFindingsRuntimeTest {
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

  private fun finding(ordinal: Int, severity: String, location: String) = UnaddressedFinding(
    issueKey = "SKILL-135",
    subtaskId = 3,
    workflowId = "workflow-1",
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = severity,
    issueCategory = "persistence",
    location = location,
    summary = "Finding $ordinal",
  )
}
