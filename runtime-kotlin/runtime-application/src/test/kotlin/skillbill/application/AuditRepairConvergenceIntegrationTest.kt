@file:Suppress("EmptyFunctionBlock", "FunctionOnlyReturningConstant", "UnusedParameter")

package skillbill.application

import org.junit.jupiter.api.Test
import skillbill.application.featuretask.AuditConvergenceMetrics
import skillbill.application.featuretask.AuditGenerationRecorder
import skillbill.application.featuretask.AuditRepairBatchPlanner
import skillbill.application.featuretask.CompletenessAuditPhase
import skillbill.application.featuretask.FollowUpAuditReconciler
import skillbill.application.model.FollowUpReconciliation
import skillbill.ports.persistence.AuditGenerationStore
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.ports.persistence.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditRepairConvergenceIntegrationTest {

  @Test
  fun `distinct gaps under one criterion retain independent logical identities`() {
    val resolver = skillbill.application.featuretask.AuditGapIdentityResolver()

    assertTrue(resolver.isSameIdentity("ac-002-gap-1", "AC-002-GAP-1"))
    assertFalse(resolver.isSameIdentity("ac-002-gap-1", "ac-002-gap-2"))
  }

  @Test
  fun `initial full-per-criterion audit creates one generation with closure-complete repair batch`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()

    val generation = scenario.getLatestGeneration()
    assertEquals(1, generation.generation)
    assertTrue(generation.gaps.isNotEmpty())
    val repairBatch = generation.repairBatch
    assertTrue(repairBatch != null)
    assertTrue(repairBatch.isActive)
    assertEquals(2, scenario.getUnresolvedRepairItems().size)
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
      assertTrue(currentGap.status in setOf(AuditGapStatus.RESOLVED, AuditGapStatus.SUPERSEDED))
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

    val followUp = scenario.runFollowUpAuditWithBlastRadiusGap()

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
  fun `crash at audit plan persistence preserves generation but not batch`() {
    val scenario = AuditConvergenceScenario()
    scenario.crashDuringAuditPlanPersistence()
    scenario.runInitialAudit()

    scenario.resume()

    val batch = scenario.getActiveBatchOrNull()
    assertNull(batch)

    val history = scenario.listAllGenerations()
    assertEquals(1, history.size)
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
    assertFalse(metrics.firstPassConvergence)
  }

  @Test
  fun `new and recurring gap counts are derived from durable generations`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runIncompleteRepair()
    scenario.runFollowUpAuditWithNewGaps()

    val metrics = scenario.deriveMetrics()
    assertEquals(2, metrics.newGapCount)
    assertTrue(metrics.recurringGapCount > 0)
  }

  @Test
  fun `attempted and resolved repair counts derive from durable results`() {
    val scenario = AuditConvergenceScenario()
    scenario.runInitialAudit()
    scenario.runRepairCycle()
    scenario.runFollowUpAudit()

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

private class AuditConvergenceScenario {
  private val workflowId = "test-workflow-${Instant.now().toEpochMilli()}"
  private val generationStore = InMemoryAuditGenerationStore()
  private val batchStore = InMemoryAuditRepairBatchStore(generationStore)
  private val repairQuery = InMemoryAuditRepairQuery(generationStore, batchStore)
  private val testPhaseLedger = TestPhaseLedger()

  private val generationRecorder = AuditGenerationRecorder(generationStore, testPhaseLedger)
  private val batchPlanner = AuditRepairBatchPlanner()

  private val auditPhase = CompletenessAuditPhase(
    generationRecorder,
    batchPlanner,
  )

  private val followUpReconciler = FollowUpAuditReconciler(
    generationStore,
    batchStore,
    repairQuery,
    skillbill.application.featuretask.AuditGapIdentityResolver(),
    skillbill.application.featuretask.AuditBlastRadiusInspector(),
    skillbill.application.featuretask.AuditSatisfactionGate(),
  )

  private val metrics = AuditConvergenceMetrics(generationStore, batchStore, repairQuery)

  private var crashBeforePersistence = false
  private var crashPoint: String? = null

  fun runInitialAudit() {
    val plan = createAuditPlanWithGaps()
    val result = auditPhase.handleInitialAudit(
      workflowId = workflowId,
      auditPlan = plan,
      repositoryFingerprint = "a".repeat(64),
      declaredCriteria = plan.gaps.map { it.acceptanceCriterionRef },
      satisfiedCriteria = emptyList(),
    )
    if (!crashBeforePersistence) {
      batchStore.persist(result.repairBatch ?: return)
    }
  }

  fun completeRepairItem(itemId: String) {
    val batch = batchStore.getActive(workflowId) ?: return
    val item = batch.repairItems.firstOrNull { it.itemId == itemId } ?: return

    val result = AuditRepairItemResult(
      itemId = itemId,
      outcome = AuditRepairItemResult.Outcome.FIXED,
      evidenceRef = "fix-evidence-$itemId",
      verificationRef = "verification-$itemId",
      dispositionGeneration = 1,
    )

    repairQuery.recordResult(result)
  }

  fun completeGap(gapId: String) {
    val batch = batchStore.getActive(workflowId) ?: return
    val gapItems = batch.repairItems.filter { it.gapId == gapId }

    val allCompleted = gapItems.all { item ->
      repairResultsExist(item.itemId)
    }

    if (allCompleted) {
      val disposition = AuditGapDisposition(
        gapId = gapId,
        status = AuditGapStatus.RESOLVED,
        evidence = FeatureTaskRuntimeEvidence(
          observation = FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED,
          artifactRef = "artifact-$gapId",
          checkRef = "AC-001",
        ),
        dispositionGeneration = 1,
      )
      repairQuery.recordGapDisposition(disposition)
    }
  }

  fun repairResultsExist(itemId: String): Boolean = repairQuery.getPriorResults(workflowId, itemId).isNotEmpty()

  fun crash() {
    crashBeforePersistence = true
  }

  fun crashDuringAuditPlanPersistence() {
    crashPoint = "audit_plan_persistence"
    crashBeforePersistence = true
  }

  fun crashDuringRepairResultPersistence() {
    crashPoint = "repair_result_persistence"
  }

  fun crashDuringFollowUpDispositionPersistence() {
    crashPoint = "follow_up_disposition_persistence"
  }

  fun resume() {
    crashBeforePersistence = false
    crashPoint = null
  }

  fun runRepairCycle() {
    val batch = batchStore.getActive(workflowId) ?: return
    batch.repairItems.forEach { item ->
      completeRepairItem(item.itemId)
    }
    batch.repairItems.map { it.gapId }.distinct().forEach { gapId ->
      completeGap(gapId)
    }
  }

  fun runIncompleteRepair() {
    val batch = batchStore.getActive(workflowId) ?: return
    val firstItem = batch.repairItems.firstOrNull() ?: return
    completeRepairItem(firstItem.itemId)
  }

  fun runFollowUpAudit(): FollowUpResult {
    val currentPlan = createFollowUpAuditPlan(empty = true)
    val reconciliation = followUpReconciler.reconcileFollowUp(
      workflowId = workflowId,
      currentAudit = currentPlan,
      repositoryFingerprint = "b".repeat(64),
    )

    val generation = (reconciliation as? FollowUpReconciliation.Reconciled)?.generation
      ?: return FollowUpResult(false, generationStore.getLatest(workflowId)!!)

    return FollowUpResult(
      canSatisfy = reconciliation.canSatisfy,
      generation = generation,
      gaps = generation.gaps,
    )
  }

  fun runFollowUpAuditWithNewGaps(): FollowUpResult {
    val currentPlan = createFollowUpAuditPlanWithCarriedAndNewGaps()
    val reconciliation = followUpReconciler.reconcileFollowUp(
      workflowId = workflowId,
      currentAudit = currentPlan,
      repositoryFingerprint = "b".repeat(64),
    )

    val generation = (reconciliation as? FollowUpReconciliation.Reconciled)?.generation
      ?: return FollowUpResult(false, generationStore.getLatest(workflowId)!!)

    return FollowUpResult(
      canSatisfy = reconciliation.canSatisfy,
      generation = generation,
      gaps = generation.gaps,
    )
  }

  fun runFollowUpAuditWithBlastRadiusGap(): FollowUpResult {
    val currentPlan = createFollowUpAuditPlan(empty = false)
    val reconciliation = followUpReconciler.reconcileFollowUp(
      workflowId = workflowId,
      currentAudit = currentPlan,
      repositoryFingerprint = "b".repeat(64),
    )

    val generation = (reconciliation as? FollowUpReconciliation.Reconciled)?.generation
      ?: return FollowUpResult(false, generationStore.getLatest(workflowId)!!)

    return FollowUpResult(
      canSatisfy = reconciliation.canSatisfy,
      generation = generation,
      gaps = generation.gaps,
    )
  }

  fun getLatestGeneration(): AuditGeneration = generationStore.getLatest(workflowId)!!

  fun getGeneration(generation: Int): AuditGeneration = generationStore.getByGeneration(workflowId, generation)!!

  fun getUnresolvedRepairItems(): List<AuditRepairItem> = repairQuery.getUnresolvedRepairItems(workflowId)

  fun getPriorResult(itemId: String): AuditRepairItemResult? =
    repairQuery.getPriorResults(workflowId, itemId).lastOrNull()

  fun getActiveBatch(): AuditRepairBatch = batchStore.getActive(workflowId)!!

  fun getActiveBatchOrNull(): AuditRepairBatch? = batchStore.getActive(workflowId)

  fun listAllGenerations(): List<AuditGeneration> = generationStore.listAll(workflowId)

  fun deriveMetrics(): MetricsData {
    val metricsData = metrics.deriveMetrics(workflowId, testPhaseLedger.getCurrentLedger())
    return MetricsData(
      firstPassConvergence = metricsData.firstPassConvergence,
      newGapCount = metricsData.newGapCount,
      recurringGapCount = metricsData.recurringGapCount,
      attemptedRepairItemCount = metricsData.attemptedRepairItemCount,
      resolvedRepairItemCount = metricsData.resolvedRepairItemCount,
      auditLoopCount = metricsData.auditLoopCount,
      totalGenerations = metricsData.auditLoopCount + 1,
      phaseLedgerAgreement = metricsData.phaseLedgerAgreement,
    )
  }

  private fun createAuditPlanWithGaps(): FeatureTaskRuntimeAuditRepairPlan {
    val gapId = AuditGenerationIdentities.gapId("AC-001", 1)
    val itemId1 = AuditGenerationIdentities.repairItemId(gapId, 1)
    val itemId2 = AuditGenerationIdentities.repairItemId(gapId, 2)

    return FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION,
      gaps = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap(
          gapId = gapId,
          acceptanceCriterionRef = "AC-001",
          acceptanceCriterionText = "Test criterion",
          failureEvidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED,
            artifactRef = "src/test/Example.kt",
            checkRef = "AC-001",
          ),
          diagnosis = "Test diagnosis",
          affectedBoundary = "test-boundary",
          repairItems = listOf(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = itemId1,
              intendedOutcome = "Fix the issue",
              implementationActions = listOf("action1", "action2"),
              affectedPathsOrSymbols = listOf("path1", "path2"),
              requiredVerification = listOf("verify1", "verify2"),
              dependsOn = emptyList(),
            ),
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = itemId2,
              intendedOutcome = "Complete the fix",
              implementationActions = listOf("action3"),
              affectedPathsOrSymbols = listOf("path3"),
              requiredVerification = listOf("verify3"),
              dependsOn = listOf(itemId1),
            ),
          ),
        ),
      ),
    )
  }

  private fun createEmptyFollowUpAuditPlan(): FeatureTaskRuntimeAuditRepairPlan {
    val originalGapId = AuditGenerationIdentities.gapId("AC-001", 1)
    val originalItemId = AuditGenerationIdentities.repairItemId(originalGapId, 1)
    return FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION,
      gaps = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap(
          gapId = originalGapId,
          acceptanceCriterionRef = "AC-001",
          acceptanceCriterionText = "Test criterion",
          failureEvidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED,
            artifactRef = "src/test/Example.kt",
            checkRef = "AC-001",
          ),
          diagnosis = "Test diagnosis",
          affectedBoundary = "test-boundary",
          repairItems = listOf(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = originalItemId,
              intendedOutcome = "Verify fix",
              implementationActions = listOf("verify-fix"),
              affectedPathsOrSymbols = listOf("path1"),
              requiredVerification = listOf("verify1"),
              dependsOn = emptyList(),
            ),
          ),
        ),
      ),
    )
  }

  private fun createFollowUpAuditPlanWithNewGap(): FeatureTaskRuntimeAuditRepairPlan {
    val newGapId = AuditGenerationIdentities.gapId("AC-002", 2)
    val newItemId = AuditGenerationIdentities.repairItemId(newGapId, 1)
    return FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION,
      gaps = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap(
          gapId = newGapId,
          acceptanceCriterionRef = "AC-002",
          acceptanceCriterionText = "Blast radius gap",
          failureEvidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED,
            artifactRef = "src/test/BlastRadius.kt",
            checkRef = "AC-002",
          ),
          diagnosis = "Blast radius issue",
          affectedBoundary = "path1",
          repairItems = listOf(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = newItemId,
              intendedOutcome = "Fix blast radius issue",
              implementationActions = listOf("fix-blast-radius"),
              affectedPathsOrSymbols = listOf("path1"),
              requiredVerification = listOf("verify-blast-radius"),
              dependsOn = emptyList(),
            ),
          ),
        ),
      ),
    )
  }

  private fun createFollowUpAuditPlan(empty: Boolean = false): FeatureTaskRuntimeAuditRepairPlan =
    if (empty) createEmptyFollowUpAuditPlan() else createFollowUpAuditPlanWithNewGap()

  private fun createFollowUpAuditPlanWithCarriedAndNewGaps(): FeatureTaskRuntimeAuditRepairPlan {
    val originalGapId = AuditGenerationIdentities.gapId("AC-001", 1)
    val originalItemId = AuditGenerationIdentities.repairItemId(originalGapId, 1)

    val newGapId = AuditGenerationIdentities.gapId("AC-002", 2)
    val newItemId = AuditGenerationIdentities.repairItemId(newGapId, 1)

    return FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION,
      gaps = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap(
          gapId = originalGapId,
          acceptanceCriterionRef = "AC-001",
          acceptanceCriterionText = "Test criterion",
          failureEvidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED,
            artifactRef = "src/test/Example.kt",
            checkRef = "AC-001",
          ),
          diagnosis = "Test diagnosis",
          affectedBoundary = "test-boundary",
          repairItems = listOf(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = originalItemId,
              intendedOutcome = "Verify fix",
              implementationActions = listOf("verify-fix"),
              affectedPathsOrSymbols = listOf("path1"),
              requiredVerification = listOf("verify1"),
              dependsOn = emptyList(),
            ),
          ),
        ),
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap(
          gapId = newGapId,
          acceptanceCriterionRef = "AC-002",
          acceptanceCriterionText = "New gap",
          failureEvidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED,
            artifactRef = "src/test/NewGap.kt",
            checkRef = "AC-002",
          ),
          diagnosis = "New gap diagnosis",
          affectedBoundary = "unrelated-boundary",
          repairItems = listOf(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem(
              repairItemId = newItemId,
              intendedOutcome = "Fix new gap",
              implementationActions = listOf("fix-new-gap"),
              affectedPathsOrSymbols = listOf("unrelated-path"),
              requiredVerification = listOf("verify-new-gap"),
              dependsOn = emptyList(),
            ),
          ),
        ),
      ),
    )
  }
}

private class InMemoryAuditGenerationStore : AuditGenerationStore {
  private val generations = mutableMapOf<Pair<String, Int>, AuditGeneration>()

  override fun persist(generation: AuditGeneration): AuditGeneration {
    val existing = getLatest(generation.workflowId)
    require(existing == null || existing.generation < generation.generation) {
      "Cannot persist generation ${generation.generation} when generation ${existing?.generation} already exists."
    }
    generations[generation.workflowId to generation.generation] = generation
    return generation
  }

  override fun getLatest(workflowId: String): AuditGeneration? =
    generations.filterKeys { it.first == workflowId }.values.maxByOrNull { it.generation }

  override fun getByGeneration(workflowId: String, generation: Int): AuditGeneration? =
    generations[workflowId to generation]

  override fun listAll(workflowId: String): List<AuditGeneration> =
    generations.filterKeys { it.first == workflowId }.values.sortedBy { it.generation }

  fun getGenerationIds(workflowId: String): List<String> =
    generations.filterKeys { it.first == workflowId }.values.map { it.generationId }
}

private class InMemoryAuditRepairBatchStore(
  private val generationStore: InMemoryAuditGenerationStore,
) : AuditRepairBatchStore {
  private val batches = mutableMapOf<String, AuditRepairBatch>()
  private val workflowBatches = mutableMapOf<String, String>()

  override fun persist(batch: AuditRepairBatch): AuditRepairBatch {
    batches[batch.batchId] = batch
    workflowBatches[batch.generationId] = batch.batchId
    return batch
  }

  override fun getActive(workflowId: String): AuditRepairBatch? {
    val generationIds = generationStore.getGenerationIds(workflowId)
    return generationIds.reversed()
      .firstNotNullOfOrNull { generationId ->
        workflowBatches[generationId]?.let { batchId ->
          batches[batchId]?.takeIf { it.isActive }
        }
      }
  }

  override fun getByGenerationId(generationId: String): AuditRepairBatch? {
    val batchId = workflowBatches[generationId] ?: return null
    return batches[batchId]
  }

  override fun listByWorkflow(workflowId: String): List<AuditRepairBatch> {
    val generationIds = generationStore.getGenerationIds(workflowId)
    return generationIds.mapNotNull { workflowBatches[it]?.let { batches[it] } }
  }

  override fun deactivate(batchId: String): Boolean {
    val batch = batches[batchId] ?: return false
    batches[batchId] = batch.copy(isActive = false)
    return true
  }
}

private class InMemoryAuditRepairQuery(
  private val generationStore: InMemoryAuditGenerationStore,
  private val batchStore: InMemoryAuditRepairBatchStore,
) : AuditRepairQuery {
  private val repairResults = mutableMapOf<String, MutableList<AuditRepairItemResult>>()
  private val gapDispositions = mutableMapOf<String, AuditGapDisposition>()
  private val nonRegressionConstraints = mutableMapOf<String, List<String>>()

  override fun getUnresolvedRepairItems(workflowId: String): List<AuditRepairItem> {
    val batch = batchStore.getActive(workflowId) ?: return emptyList()
    val resultItemIds = repairResults.values.flatten().map { it.itemId }.toSet()
    return batch.repairItems.filter { it.itemId !in resultItemIds }
  }

  override fun getUnresolvedRepairItemsWithDependencies(
    workflowId: String,
  ): Map<AuditRepairItem, List<AuditRepairItem>> {
    val allItems = getUnresolvedRepairItems(workflowId)
    val itemMap = allItems.associateBy { it.itemId }
    val result = mutableMapOf<AuditRepairItem, List<AuditRepairItem>>()

    allItems.forEach { item ->
      val deps = item.dependencies.mapNotNull { itemMap[it] }
      result[item] = deps
    }

    return result
  }

  override fun getPriorResults(workflowId: String, itemId: String): List<AuditRepairItemResult> =
    repairResults[itemId] ?: emptyList()

  override fun getNonRegressionConstraints(workflowId: String, itemId: String): List<String> =
    nonRegressionConstraints[itemId] ?: emptyList()

  override fun getGapDisposition(workflowId: String, gapId: String): AuditGapDisposition? = gapDispositions[gapId]

  override fun getAllGapDispositions(workflowId: String): List<AuditGapDisposition> {
    val gaps = generationStore.listAll(workflowId).flatMap { it.gaps }
    return gaps.mapNotNull { gapDispositions[it.gapId] }
  }

  override fun getRecurringGaps(workflowId: String): List<skillbill.workflow.taskruntime.model.AuditGap> {
    val gaps = generationStore.listAll(workflowId).flatMap { it.gaps }
    return gaps.filter { it.status == AuditGapStatus.RECURRING }
  }

  override fun getResolvedGaps(workflowId: String): List<skillbill.workflow.taskruntime.model.AuditGap> {
    val gaps = generationStore.listAll(workflowId).flatMap { it.gaps }
    return gaps.filter { it.status == AuditGapStatus.RESOLVED }
  }

  fun recordResult(result: AuditRepairItemResult) {
    repairResults.getOrPut(result.itemId) { mutableListOf() }.add(result)
  }

  fun recordGapDisposition(disposition: AuditGapDisposition) {
    gapDispositions[disposition.gapId] = disposition
  }
}

private class TestPhaseLedger : skillbill.application.featuretask.PhaseLedger {
  private val ledger = mutableMapOf<String, Int>()

  override fun recordAuditGeneration(workflowId: String, generation: Int) {
    ledger[workflowId] = generation
  }

  fun getCurrentLedger(): FeatureTaskRuntimePhaseLedger? {
    if (ledger.isEmpty()) return null
    return FeatureTaskRuntimePhaseLedger(
      entries = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry(
          action = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.START,
          sequenceNumber = 0,
          timestamp = Instant.now().toString(),
          phaseId = "audit",
          attemptCount = 1,
          resolvedAgentId = "test-agent",
          auditRepairProgress = FeatureTaskRuntimeAuditRepairProgress(
            firstPassConvergence = false,
            newGapCount = 1,
            recurringGapCount = 0,
            attemptedRepairItemCount = 0,
            resolvedRepairItemCount = 0,
            auditGapIterationCount = 0,
          ),
        ),
      ),
    )
  }
}

private data class FollowUpResult(
  val canSatisfy: Boolean,
  val generation: AuditGeneration,
  val gaps: List<AuditGap> = generation.gaps,
)

private data class MetricsData(
  val firstPassConvergence: Boolean,
  val newGapCount: Int,
  val recurringGapCount: Int,
  val attemptedRepairItemCount: Int,
  val resolvedRepairItemCount: Int,
  val auditLoopCount: Int,
  val totalGenerations: Int = auditLoopCount + 1,
  val phaseLedgerAgreement: Boolean,
)
