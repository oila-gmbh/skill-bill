package skillbill.application.featuretask

import skillbill.application.model.AuditGenerationResult
import skillbill.ports.persistence.AuditGenerationStore
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.RepositoryCheckpoint
import java.time.Instant
import java.util.UUID

class CompletenessAuditPhase(
  private val generationRecorder: AuditGenerationRecorder,
  private val batchPlanner: AuditRepairBatchPlanner,
) {
  companion object {
    fun handleInitialAudit(
      auditPlan: FeatureTaskRuntimeAuditRepairPlan,
      repositoryFingerprint: String?,
      edgeIteration: Int?,
      declaredCriteria: List<String>,
    ): FeatureTaskRuntimeAuditRepairState {
      require(declaredCriteria.isNotEmpty()) {
        "Initial audit requires the complete declared acceptance-criterion scope."
      }
      return FeatureTaskRuntimeAuditRepairReconciler.reconcile(
        AuditRepairReconciliation(
          prior = null,
          latestPlan = auditPlan,
          repairResults = emptyList(),
          dispositions = null,
          repositoryFingerprint = repositoryFingerprint,
          edgeIteration = edgeIteration,
          auditWrite = true,
          auditScopeCriterionRefs = declaredCriteria,
        ),
      )
    }
  }

  fun handleInitialAudit(
    workflowId: String,
    auditPlan: FeatureTaskRuntimeAuditRepairPlan,
    repositoryFingerprint: String,
    declaredCriteria: List<String>,
    satisfiedCriteria: List<String>,
  ): AuditGenerationResult {
    val now = Instant.now().toString()
    val generation = 1
    val generationId = AuditGenerationIdentities.generationId(workflowId, generation)

    val checkpoint = RepositoryCheckpoint(
      fingerprint = repositoryFingerprint,
      evidenceRef = "audit-$generationId",
    )

    val gaps = auditPlan.gaps.map { planGap ->
      AuditGap(
        gapId = planGap.gapId,
        acceptanceCriterionRef = planGap.acceptanceCriterionRef,
        acceptanceCriterionText = planGap.acceptanceCriterionText,
        failureEvidence = planGap.failureEvidence,
        diagnosis = planGap.diagnosis,
        affectedBoundary = planGap.affectedBoundary,
        status = AuditGapStatus.NEW,
        recurrence = 0,
        firstSeenGeneration = generation,
      )
    }
    val satisfied = satisfiedCriteria.toSet()
    val gapped = gaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    require(satisfied.intersect(gapped).isEmpty()) {
      "Initial audit criteria cannot be both satisfied and gapped."
    }
    require(satisfied + gapped == declaredCriteria.toSet()) {
      "Initial audit must disposition every declared acceptance criterion exactly once; " +
        "declared=${declaredCriteria.sorted()} satisfied=${satisfied.sorted()} gapped=${gapped.sorted()}."
    }

    val repairBatch = batchPlanner.planClosureCompleteBatch(
      generationId = generationId,
      gaps = gaps,
      plan = auditPlan,
    )

    val auditGeneration = AuditGeneration(
      generationId = generationId,
      workflowId = workflowId,
      generation = generation,
      repositoryCheckpoint = checkpoint,
      satisfiedCriterionRefs = satisfiedCriteria,
      gaps = gaps,
      repairBatch = repairBatch,
      createdAt = now,
    )

    val persisted = generationRecorder.recordWithLedgerAdvance(auditGeneration)

    return AuditGenerationResult(
      generation = persisted,
      repairBatch = repairBatch,
    )
  }

  fun verifyAllCriteriaDisposed(generation: AuditGeneration): Boolean {
    val allGapsDisposed = generation.gaps.all { gap ->
      gap.status in setOf(AuditGapStatus.RESOLVED, AuditGapStatus.SUPERSEDED)
    }
    val allSatisfied = generation.satisfiedCriterionRefs.isNotEmpty()
    return allGapsDisposed || allSatisfied
  }
}

class AuditGenerationRecorder(
  private val store: AuditGenerationStore,
  private val phaseLedger: PhaseLedger,
) {
  fun recordWithLedgerAdvance(generation: AuditGeneration): AuditGeneration {
    val recorded = store.persist(generation)
    phaseLedger.recordAuditGeneration(generation.workflowId, generation.generation)
    return recorded
  }

  fun getLatest(workflowId: String): AuditGeneration? = store.getLatest(workflowId)
}

class AuditRepairBatchPlanner {
  fun planClosureCompleteBatch(
    generationId: String,
    gaps: List<AuditGap>,
    plan: FeatureTaskRuntimeAuditRepairPlan,
  ): AuditRepairBatch? {
    if (gaps.isEmpty()) return null

    val batchId = "batch-$generationId-${UUID.randomUUID()}"
    val repairItems = plan.gaps.flatMap { planGap ->
      planGap.repairItems.map { planItem ->
        AuditRepairItem(
          itemId = planItem.repairItemId,
          gapId = planGap.gapId,
          intendedOutcome = planItem.intendedOutcome,
          implementationActions = planItem.implementationActions,
          affectedPathsOrSymbols = planItem.affectedPathsOrSymbols,
          requiredVerification = planItem.requiredVerification,
          dependencies = planItem.dependsOn,
        )
      }
    }

    val dependencies = buildDependencies(repairItems)

    return AuditRepairBatch(
      batchId = batchId,
      generationId = generationId,
      repairItems = repairItems,
      dependencies = dependencies,
      isActive = true,
    )
  }

  private fun buildDependencies(items: List<AuditRepairItem>): Map<String, List<String>> {
    val itemIds = items.associateBy { it.itemId }
    val dependencies = mutableMapOf<String, List<String>>()

    items.forEach { item ->
      val deps = item.dependencies.mapNotNull { itemIds[it] }
      dependencies[item.itemId] = deps.map { it.itemId }
    }

    return dependencies
  }
}

interface PhaseLedger {
  fun recordAuditGeneration(workflowId: String, generation: Int)
}
