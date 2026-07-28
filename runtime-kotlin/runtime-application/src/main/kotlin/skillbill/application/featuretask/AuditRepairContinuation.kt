package skillbill.application.featuretask

import skillbill.ports.persistence.AuditGapDisposition
import skillbill.ports.persistence.AuditRepairBatch
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairItem
import skillbill.ports.persistence.AuditRepairItemResult
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import java.util.UUID

class AuditRepairContinuationBuilder(
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
) {
  fun buildContinuation(workflowId: String): AuditRepairContinuation? {
    val activeBatch = batchStore.getActive(workflowId) ?: return null

    val allItems = activeBatch.repairItems
    val unresolvedItems = repairQuery.getUnresolvedRepairItems(workflowId)

    if (unresolvedItems.isEmpty()) return null

    val orderedItems = topologicalOrder(unresolvedItems, activeBatch.dependencies)

    val itemResults = orderedItems.associateWith { item ->
      repairQuery.getPriorResults(item.itemId).lastOrNull()
    }

    val nonRegressionConstraints = orderedItems.associateWith { item ->
      repairQuery.getNonRegressionConstraints(item.itemId)
    }

    val gapDispositions = orderedItems.mapNotNull { item ->
      repairQuery.getGapDisposition(item.gapId)
    }.associateBy { it.gapId }

    return AuditRepairContinuation(
      batchId = activeBatch.batchId,
      workflowId = workflowId,
      unresolvedItems = orderedItems,
      itemResults = itemResults,
      nonRegressionConstraints = nonRegressionConstraints,
      gapDispositions = gapDispositions,
    )
  }

  private fun topologicalOrder(
    items: List<AuditRepairItem>,
    dependencies: Map<String, List<String>>,
  ): List<AuditRepairItem> {
    val itemMap = items.associateBy { it.itemId }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<AuditRepairItem>()

    fun visit(itemId: String) {
      if (itemId in visited) return
      val item = itemMap[itemId] ?: return
      dependencies[itemId]?.forEach { visit(it) }
      visited.add(itemId)
      result.add(item)
    }

    items.forEach { visit(it.itemId) }

    return result
  }

  data class AuditRepairContinuation(
    val batchId: String,
    val workflowId: String,
    val unresolvedItems: List<AuditRepairItem>,
    val itemResults: Map<AuditRepairItem, AuditRepairItemResult?>,
    val nonRegressionConstraints: Map<AuditRepairItem, List<String>>,
    val gapDispositions: Map<String, AuditGapDisposition>,
  )
}

class AuditRepairCompletionGate(
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
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

  sealed interface CompletionDecision {
    data object NoActiveBatch : CompletionDecision
    data class IncompleteItems(val missingItemIds: List<String>) : CompletionDecision
    data object NonTerminalItems : CompletionDecision
    data object CanComplete : CompletionDecision
  }
}

class ImplementationReentryRouter(
  private val continuationBuilder: AuditRepairContinuationBuilder,
  private val completionGate: AuditRepairCompletionGate,
) {
  fun routeReentry(workflowId: String): ReentryDecision {
    val continuation = continuationBuilder.buildContinuation(workflowId)

    if (continuation == null) {
      return ReentryDecision.NoUnresolvedWork
    }

    return ReentryDecision.RequiresImplementation(continuation)
  }

  fun verifyCompletion(
    workflowId: String,
    results: List<FeatureTaskRuntimeRepairItemResult>,
  ): ReentryCompletion {
    val decision = completionGate.canReportCompleted(workflowId, results)

    return when (decision) {
      is AuditRepairCompletionGate.CompletionDecision.NoActiveBatch ->
        ReentryCompletion.NoActiveBatch
      is AuditRepairCompletionGate.CompletionDecision.IncompleteItems ->
        ReentryCompletion.IncompleteItems(decision.missingItemIds)
      is AuditRepairCompletionGate.CompletionDecision.NonTerminalItems ->
        ReentryCompletion.NonTerminalItems
      is AuditRepairCompletionGate.CompletionDecision.CanComplete ->
        ReentryCompletion.CanComplete
    }
  }

  sealed interface ReentryDecision {
    data object NoUnresolvedWork : ReentryDecision
    data class RequiresImplementation(val continuation: AuditRepairContinuationBuilder.AuditRepairContinuation) : ReentryDecision
  }

  sealed interface ReentryCompletion {
    data object NoActiveBatch : ReentryCompletion
    data class IncompleteItems(val missingItemIds: List<String>) : ReentryCompletion
    data object NonTerminalItems : ReentryCompletion
    data object CanComplete : ReentryCompletion
  }
}
