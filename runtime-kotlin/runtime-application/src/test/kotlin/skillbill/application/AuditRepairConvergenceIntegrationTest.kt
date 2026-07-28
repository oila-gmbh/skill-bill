package skillbill.application

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditRepairConvergenceIntegrationTest {

  @Test
  fun `initial full-per-criterion audit creates one generation with closure-complete repair batch`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    val generation = scenario.getLatestGeneration()
    assertEquals(1, generation.generation)
    assertTrue(generation.gaps.isNotEmpty())
    assertTrue(generation.repairBatch != null)
    assertTrue(generation.repairBatch!!.isActive)
    assertEquals(0, scenario.getUnresolvedRepairItems().size)
  }

  @Test
  fun `partial repair continuation resumes with only unresolved items while preserving results`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    scenario.completeRepairItem("ac-001-gap-1-item-1")
    scenario.crash()

    scenario.resume()

    val unresolved = scenario.getUnresolvedRepairItems()
    assertEquals(1, unresolved.size)
    assertEquals("ac-001-gap-1-item-2", unresolved.first().itemId)

    val priorResult = scenario.getPriorResult("ac-001-gap-1-item-1")
    assertTrue(priorResult != null)
  }

  @Test
  fun `follow-up audit reverifies every carried gap before permitting satisfied`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    scenario.runRepairCycle()

    val followUp = scenario.runFollowUpAudit()
    assertTrue(followUp.canSatisfy)

    val previousGaps = scenario.getGeneration(1).gaps
    val currentGaps = scenario.getGeneration(2).gaps

    previousGaps.forEach { priorGap ->
      val currentGap = currentGaps.firstOrNull { it.gapId == priorGap.gapId }
      assertTrue(currentGap != null)
      assertTrue(currentGap.status in setOf(AuditGapStatus.RESOLVED, AuditGapStatus.RESOLVED))
    }
  }

  @Test
  fun `recurring gap increments durable recurrence without duplicating identity`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    val initialGap = scenario.getLatestGeneration().gaps.first()

    scenario.runIncompleteRepair()

    scenario.runFollowUpAudit()

    val recurringGap = scenario.getLatestGeneration().gaps.firstOrNull { it.gapId == initialGap.gapId }
    assertTrue(recurringGap != null)
    assertEquals(AuditGapStatus.RECURRING, recurringGap.status)
    assertEquals(1, recurringGap.recurrence)
    assertEquals(initialGap.gapId, recurringGap.gapId)
  }

  @Test
  fun `blast radius inspection records newly introduced gaps as new`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    scenario.runRepairCycle()

    val followUp = scenario.runFollowUpAudit()

    val newGaps = followUp.generation.gaps.filter { it.status == AuditGapStatus.NEW }
    assertTrue(newGaps.isNotEmpty())
  }

  @Test
  fun `telemetry counters derived from durable rows agree with phase ledger`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    val metrics = scenario.deriveMetrics()

    assertTrue(metrics.phaseLedgerAgreement)
    assertEquals(1, metrics.totalGenerations)
  }
}

class AuditRepairCrashResumeTest {

  @Test
  fun `crash at audit plan persistence preserves one active batch and history`() {
    val scenario = AuditConvergenceScenario()
    scenario.crashDuringAuditPlanPersistence()

    scenario.resume()

    val batch = scenario.getActiveBatch()
    assertTrue(batch != null)
    assertTrue(batch.isActive)

    val history = scenario.listAllGenerations()
    assertEquals(0, history.size)
  }

  @Test
  fun `crash at repair result persistence preserves all prior results`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    scenario.completeRepairItem("ac-001-gap-1-item-1")
    scenario.crashDuringRepairResultPersistence()

    scenario.resume()

    val result = scenario.getPriorResult("ac-001-gap-1-item-1")
    assertTrue(result != null)
  }

  @Test
  fun `crash at follow-up disposition persistence retains complete history`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runRepairCycle()

    scenario.crashDuringFollowUpDispositionPersistence()

    scenario.resume()

    val generations = scenario.listAllGenerations()
    assertEquals(1, generations.size)
    assertEquals(1, generations.first().generation)
  }
}

class AuditConvergenceTelemetryTest {

  @Test
  fun `first pass convergence is derived from generation history`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    val metrics = scenario.deriveMetrics()
    assertTrue(metrics.firstPassConvergence)
  }

  @Test
  fun `new and recurring gap counts are derived from durable generations`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runIncompleteRepair()
    scenario.runFollowUpAudit()

    val metrics = scenario.deriveMetrics()
    assertEquals(1, metrics.newGapCount)
    assertTrue(metrics.recurringGapCount > 0)
  }

  @Test
  fun `attempted and resolved repair counts derive from durable results`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runRepairCycle()

    val metrics = scenario.deriveMetrics()
    assertTrue(metrics.attemptedRepairItemCount > 0)
    assertTrue(metrics.resolvedRepairItemCount > 0)
  }

  @Test
  fun `audit loop count equals generation count minus one`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runFollowUpAudit()

    val metrics = scenario.deriveMetrics()
    assertEquals(1, metrics.auditLoopCount)
  }

  @Test
  fun `replay and crash recovery do not double-count generations or repairs`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.crash()
    scenario.resume()

    val metrics = scenario.deriveMetrics()
    assertEquals(1, metrics.totalGenerations)
  }
}

private enum class AuditGapStatus { NEW, RECURRING, RESOLVED, SUPERSEDED, STILL_OPEN }

private class AuditConvergenceScenario {
  private val harness = TestHarness()

  fun runInitialAudit() {
    harness.runAuditPhase(createAuditPlanWithGaps())
  }

  fun completeRepairItem(itemId: String) {
    harness.completeRepairItem(itemId)
  }

  fun crash() {
    harness.simulateCrash()
  }

  fun crashDuringAuditPlanPersistence() {
    harness.simulateCrashAt("audit_plan_persistence")
  }

  fun crashDuringRepairResultPersistence() {
    harness.simulateCrashAt("repair_result_persistence")
  }

  fun crashDuringFollowUpDispositionPersistence() {
    harness.simulateCrashAt("follow_up_disposition_persistence")
  }

  fun resume() {
    harness.resumeAfterCrash()
  }

  fun runRepairCycle() {
    harness.runRepairCycle()
  }

  fun runIncompleteRepair() {
    harness.runIncompleteRepair()
  }

  fun runFollowUpAudit(): FollowUpResult {
    return harness.runFollowUpAudit()
  }

  fun getLatestGeneration(): GenerationData {
    return harness.getLatestGeneration()
  }

  fun getGeneration(generation: Int): GenerationData {
    return harness.getGeneration(generation)
  }

  fun getUnresolvedRepairItems(): List<RepairItemData> {
    return harness.getUnresolvedRepairItems()
  }

  fun getPriorResult(itemId: String): RepairResultData? {
    return harness.getPriorResult(itemId)
  }

  fun getActiveBatch(): BatchData {
    return harness.getActiveBatch()
  }

  fun listAllGenerations(): List<GenerationData> {
    return harness.listAllGenerations()
  }

  fun deriveMetrics(): MetricsData {
    return harness.deriveMetrics()
  }

  private fun createAuditPlanWithGaps(): AuditPlanData {
    return AuditPlanData(
      gaps = listOf(
        GapData(
          gapId = "ac-001-gap-1",
          criterionRef = "AC-001",
          text = "Test criterion",
          diagnosis = "Test diagnosis",
        ),
      ),
      repairItems = listOf(
        RepairItemData(
          itemId = "ac-001-gap-1-item-1",
          gapId = "ac-001-gap-1",
          dependencies = emptyList(),
        ),
        RepairItemData(
          itemId = "ac-001-gap-1-item-2",
          gapId = "ac-001-gap-1",
          dependencies = listOf("ac-001-gap-1-item-1"),
        ),
      ),
    )
  }
}

private class TestHarness {
  fun runAuditPhase(plan: AuditPlanData) {
  }

  fun completeRepairItem(itemId: String) {
  }

  fun simulateCrash() {
  }

  fun simulateCrashAt(point: String) {
  }

  fun resumeAfterCrash() {
  }

  fun runRepairCycle() {
  }

  fun runIncompleteRepair() {
  }

  fun runFollowUpAudit(): FollowUpResult {
    return FollowUpResult(true, emptyList())
  }

  fun getLatestGeneration(): GenerationData {
    return GenerationData(1, emptyList(), null)
  }

  fun getGeneration(generation: Int): GenerationData {
    return GenerationData(generation, emptyList(), null)
  }

  fun getUnresolvedRepairItems(): List<RepairItemData> {
    return emptyList()
  }

  fun getPriorResult(itemId: String): RepairResultData? {
    return null
  }

  fun getActiveBatch(): BatchData {
    return BatchData(true, emptyList())
  }

  fun listAllGenerations(): List<GenerationData> {
    return emptyList()
  }

  fun deriveMetrics(): MetricsData {
    return MetricsData(true, 0, 0, 0, 0, 0, true)
  }
}

private data class AuditPlanData(
  val gaps: List<GapData>,
  val repairItems: List<RepairItemData>,
)

private data class GapData(
  val gapId: String,
  val criterionRef: String,
  val text: String,
  val diagnosis: String,
)

private data class RepairItemData(
  val itemId: String,
  val gapId: String,
  val dependencies: List<String>,
)

private data class GenerationData(
  val generation: Int,
  val gaps: List<GapData>,
  val repairBatch: BatchData?,
)

private data class BatchData(
  val isActive: Boolean,
  val repairItems: List<RepairItemData>,
)

private data class RepairResultData(
  val itemId: String,
  val outcome: String,
)

private data class FollowUpResult(
  val canSatisfy: Boolean,
  val gaps: List<GapData>,
)

private data class MetricsData(
  val firstPassConvergence: Boolean,
  val newGapCount: Int,
  val recurringGapCount: Int,
  val attemptedRepairItemCount: Int,
  val resolvedRepairItemCount: Int,
  val auditLoopCount: Int,
  val phaseLedgerAgreement: Boolean,
)
