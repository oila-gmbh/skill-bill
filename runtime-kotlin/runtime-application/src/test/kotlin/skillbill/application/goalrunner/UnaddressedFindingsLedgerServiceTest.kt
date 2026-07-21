package skillbill.application.goalrunner

import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.GoalPlanningPreparationRepository
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnaddressedFindingsRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.WorkflowStateRepository
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnaddressedFindingsLedgerServiceTest {
  @Test
  fun `an unknown issue key raises the typed absent error`() {
    val service = serviceFor(InMemoryUnaddressedFindings(durableIssueKeys = setOf("SKILL-135")))

    assertFailsWith<UnaddressedFindingsLedgerAbsentError> { service.ledger("SKILL-404") }
  }

  @Test
  fun `a telemetry-disabled goal with no findings returns an explicit empty ledger`() {
    val service = serviceFor(InMemoryUnaddressedFindings(durableIssueKeys = setOf("SKILL-135")))

    val ledger = service.ledger("SKILL-135")

    assertTrue(ledger.findings.isEmpty())
    assertEquals(mapOf("blocker" to 0, "major" to 0, "minor" to 0, "nit" to 0), ledger.severityBreakdown)
  }

  @Test
  fun `a valid ledger spans every subtask of the goal and accepts the writer issue-category vocabulary`() {
    val rows = listOf(
      finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, severity = "major", category = "behavior_correctness"),
      finding(subtaskId = 3, workflowId = "wf-3", ordinal = 1, severity = "minor", category = "data_persistence"),
    )
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), rows))

    val ledger = service.ledger("SKILL-135")

    assertEquals(listOf(1, 3), ledger.findings.map { it.subtaskId })
    assertEquals(mapOf("blocker" to 0, "major" to 1, "minor" to 1, "nit" to 0), ledger.severityBreakdown)
  }

  @Test
  fun `a row outside the severity taxonomy raises the typed malformed error`() {
    val malformed = finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, severity = "catastrophic")
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), listOf(malformed)))

    assertFailsWith<InvalidUnaddressedFindingsLedgerSchemaError> { service.ledger("SKILL-135") }
  }

  @Test
  fun `a row outside the issue-category vocabulary raises the typed malformed error`() {
    val malformed = finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, category = "platform_correctness")
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), listOf(malformed)))

    assertFailsWith<InvalidUnaddressedFindingsLedgerSchemaError> { service.ledger("SKILL-135") }
  }

  private fun serviceFor(findings: InMemoryUnaddressedFindings) =
    UnaddressedFindingsLedgerService(LedgerOnlySessionFactory(findings))

  private fun finding(
    subtaskId: Int,
    workflowId: String,
    ordinal: Int,
    severity: String = "minor",
    category: String = "behavior_correctness",
  ) = UnaddressedFinding(
    issueKey = "SKILL-135",
    subtaskId = subtaskId,
    workflowId = workflowId,
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = severity,
    issueCategory = category,
    location = "src/Example.kt:$ordinal",
    summary = "Deferred finding $ordinal",
  )
}

private class InMemoryUnaddressedFindings(
  private val durableIssueKeys: Set<String>,
  private val rows: List<UnaddressedFinding> = emptyList(),
) : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) =
    error("The retrieval surface does not write.")

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> = rows.filter { it.issueKey == issueKey }

  override fun issueExists(issueKey: String): Boolean = issueKey in durableIssueKeys
}

private class LedgerOnlySessionFactory(
  private val findings: UnaddressedFindingsRepository,
) : DatabaseSessionFactory {
  override fun resolveDbPath(dbOverride: String?): Path = Path.of("/fake/runtime.db")

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = Path.of("/fake/runtime.db")
    override val unaddressedFindings: UnaddressedFindingsRepository = findings
    override val reviews: ReviewRepository
      get() = error("ReviewRepository is not exercised by the ledger retrieval surface.")
    override val learnings: LearningRepository
      get() = error("LearningRepository is not exercised by the ledger retrieval surface.")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("LifecycleTelemetryRepository is not exercised by the ledger retrieval surface.")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("TelemetryReconciliationRepository is not exercised by the ledger retrieval surface.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("TelemetryOutboxRepository is not exercised by the ledger retrieval surface.")
    override val workflowStates: WorkflowStateRepository
      get() = error("WorkflowStateRepository is not exercised by the ledger retrieval surface.")
    override val workList: WorkListRepository
      get() = error("WorkListRepository is not exercised by the ledger retrieval surface.")
    override val goalPlanningPreparations: GoalPlanningPreparationRepository
      get() = error("GoalPlanningPreparationRepository is not exercised by the ledger retrieval surface.")
  }
}
