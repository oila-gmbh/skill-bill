@file:Suppress("FunctionParameterNaming", "UnusedParameter")

package skillbill.application.featuretask

import skillbill.application.model.AuditConvergenceMetricsData
import skillbill.application.model.AuditStatusData
import skillbill.application.model.AuditTelemetry
import skillbill.ports.persistence.AuditGenerationStore
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedger

class AuditConvergenceMetrics(
  private val generationStore: AuditGenerationStore,
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
) {
  fun deriveMetrics(workflowId: String, phaseLedger: FeatureTaskRuntimePhaseLedger?): AuditConvergenceMetricsData {
    val generations = generationStore.listAll(workflowId)
    val batches = batchStore.listByWorkflow(workflowId)
    val firstPassConvergence = generations.size == 1 && generations.single().gaps.isEmpty()

    val newGaps = generations.sumOf { generation ->
      generation.gaps.count { it.status == AuditGapStatus.NEW }
    }

    val recurringGaps = generations.sumOf { gen ->
      gen.gaps.count { it.status == AuditGapStatus.RECURRING }
    }

    val allItems = batches.flatMap { it.repairItems }.distinctBy { it.itemId }
    val attemptedRepairs = allItems.count { repairQuery.getPriorResults(workflowId, it.itemId).isNotEmpty() }
    val resolvedRepairs = allItems.count { repairQuery.getPriorResults(workflowId, it.itemId).isNotEmpty() }

    val auditLoopCount = generations.size - 1

    val phaseLedgerAgreement = verifyLedgerAgreement(
      workflowId,
      phaseLedger,
      AuditConvergenceMetricsData(
        firstPassConvergence = firstPassConvergence,
        newGapCount = newGaps,
        recurringGapCount = recurringGaps,
        attemptedRepairItemCount = attemptedRepairs,
        resolvedRepairItemCount = resolvedRepairs,
        auditLoopCount = auditLoopCount,
      ),
    )

    return AuditConvergenceMetricsData(
      firstPassConvergence = firstPassConvergence,
      newGapCount = newGaps,
      recurringGapCount = recurringGaps,
      attemptedRepairItemCount = attemptedRepairs,
      resolvedRepairItemCount = resolvedRepairs,
      auditLoopCount = auditLoopCount,
      phaseLedgerAgreement = phaseLedgerAgreement,
    )
  }

  private fun verifyLedgerAgreement(
    _workflowId: String,
    phaseLedger: FeatureTaskRuntimePhaseLedger?,
    derivedMetrics: AuditConvergenceMetricsData,
  ): Boolean {
    if (phaseLedger == null) return false

    val ledgerProgress = extractProgressFromLedger(phaseLedger)

    return ledgerProgress?.let { progress ->
      progress.firstPassConvergence == derivedMetrics.firstPassConvergence &&
        progress.recurringGapCount == derivedMetrics.recurringGapCount &&
        progress.newGapCount == derivedMetrics.newGapCount &&
        progress.attemptedRepairItemCount == derivedMetrics.attemptedRepairItemCount &&
        progress.resolvedRepairItemCount == derivedMetrics.resolvedRepairItemCount &&
        progress.auditGapIterationCount == derivedMetrics.auditLoopCount
    } ?: false
  }

  private fun extractProgressFromLedger(ledger: FeatureTaskRuntimePhaseLedger): FeatureTaskRuntimeAuditRepairProgress? {
    return ledger.entries.lastOrNull { it.auditRepairProgress != null }?.auditRepairProgress
  }
}

class AuditStatusProjection(
  private val generationStore: AuditGenerationStore,
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
) {
  fun projectStatus(workflowId: String): AuditStatusData {
    val latestGeneration = generationStore.getLatest(workflowId)
    val activeBatch = batchStore.getActive(workflowId)

    val hasUnresolvedGaps = latestGeneration?.gaps?.any { gap ->
      gap.status in setOf(AuditGapStatus.NEW, AuditGapStatus.RECURRING, AuditGapStatus.STILL_OPEN)
    } ?: false

    val unresolvedItemCount = repairQuery.getUnresolvedRepairItems(workflowId).size

    val resolvedCount = latestGeneration?.gaps?.count { it.status == AuditGapStatus.RESOLVED } ?: 0

    val recurringCount = latestGeneration?.gaps?.count { it.status == AuditGapStatus.RECURRING } ?: 0

    val generationCount = generationStore.listAll(workflowId).size

    return AuditStatusData(
      workflowId = workflowId,
      currentGeneration = latestGeneration?.generation ?: 0,
      hasUnresolvedGaps = hasUnresolvedGaps,
      unresolvedItemCount = unresolvedItemCount,
      resolvedGapCount = resolvedCount,
      recurringGapCount = recurringCount,
      totalGenerations = generationCount,
      isActiveBatch = activeBatch != null,
    )
  }
}

class AuditTelemetryEmitter(
  private val metrics: AuditConvergenceMetrics,
  private val statusProjection: AuditStatusProjection,
) {
  fun emitTelemetry(workflowId: String, phaseLedger: FeatureTaskRuntimePhaseLedger?): AuditTelemetry {
    val metricsData = metrics.deriveMetrics(workflowId, phaseLedger)
    val statusData = statusProjection.projectStatus(workflowId)

    return AuditTelemetry(
      metrics = metricsData,
      status = statusData,
    )
  }
}
