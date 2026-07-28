package skillbill.application.model

import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult

data class AuditGenerationResult(
  val generation: AuditGeneration,
  val repairBatch: AuditRepairBatch?,
)

data class AuditRepairContinuation(
  val batchId: String,
  val workflowId: String,
  val unresolvedItems: List<AuditRepairItem>,
  val itemResults: Map<AuditRepairItem, AuditRepairItemResult?>,
  val nonRegressionConstraints: Map<AuditRepairItem, List<String>>,
  val gapDispositions: Map<String, AuditGapDisposition>,
)

sealed interface CompletionDecision {
  data object NoActiveBatch : CompletionDecision
  data class IncompleteItems(val missingItemIds: List<String>) : CompletionDecision
  data object NonTerminalItems : CompletionDecision
  data object CanComplete : CompletionDecision
}

sealed interface ReentryDecision {
  data object NoUnresolvedWork : ReentryDecision
  data class RequiresImplementation(val continuation: AuditRepairContinuation) : ReentryDecision
}

sealed interface ReentryCompletion {
  data object NoActiveBatch : ReentryCompletion
  data class IncompleteItems(val missingItemIds: List<String>) : ReentryCompletion
  data object NonTerminalItems : ReentryCompletion
  data object CanComplete : ReentryCompletion
}

sealed interface FollowUpReconciliation {
  data object NoPriorGeneration : FollowUpReconciliation
  data class Reconciled(val generation: AuditGeneration, val canSatisfy: Boolean) : FollowUpReconciliation
}

data class AuditConvergenceMetricsData(
  val firstPassConvergence: Boolean,
  val newGapCount: Int,
  val recurringGapCount: Int,
  val attemptedRepairItemCount: Int,
  val resolvedRepairItemCount: Int,
  val auditLoopCount: Int,
  val phaseLedgerAgreement: Boolean = true,
)

data class AuditStatusData(
  val workflowId: String,
  val currentGeneration: Int,
  val hasUnresolvedGaps: Boolean,
  val unresolvedItemCount: Int,
  val resolvedGapCount: Int,
  val recurringGapCount: Int,
  val totalGenerations: Int,
  val isActiveBatch: Boolean,
)

data class AuditTelemetry(
  val metrics: AuditConvergenceMetricsData,
  val status: AuditStatusData,
)

class AuditRepairCompletionGate(
  private val batchStore: AuditRepairBatchStore,
) {
  fun canReportCompleted(workflowId: String, results: List<FeatureTaskRuntimeRepairItemResult>): CompletionDecision {
    val activeBatch = batchStore.getActive(workflowId)
      ?: return CompletionDecision.NoActiveBatch

    val allItemIds = activeBatch.repairItems.map { it.itemId }.toSet()
    val reportedItemIds = results.map { it.repairItemId }.toSet()

    if (reportedItemIds != allItemIds) {
      val missing = allItemIds - reportedItemIds
      return CompletionDecision.IncompleteItems(missing.toList())
    }

    val allTerminal = results.all { result ->
      result.outcome in setOf(
        FeatureTaskRuntimeRepairItemOutcome.FIXED,
        FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED,
      )
    }

    if (!allTerminal) {
      return CompletionDecision.NonTerminalItems
    }

    return CompletionDecision.CanComplete
  }
}
