package skillbill.application.featuretask

import skillbill.application.model.AuditRepairCompletionGate
import skillbill.application.model.AuditRepairContinuation
import skillbill.application.model.CompletionDecision
import skillbill.application.model.ReentryCompletion
import skillbill.application.model.ReentryDecision
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult

class AuditRepairContinuationBuilder(
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
) {
  fun buildContinuation(workflowId: String): AuditRepairContinuation? {
    val activeBatch = batchStore.getActive(workflowId) ?: return null

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

  fun verifyCompletion(workflowId: String, results: List<FeatureTaskRuntimeRepairItemResult>): ReentryCompletion {
    val decision = completionGate.canReportCompleted(workflowId, results)

    return when (decision) {
      is CompletionDecision.NoActiveBatch -> ReentryCompletion.NoActiveBatch
      is CompletionDecision.IncompleteItems -> ReentryCompletion.IncompleteItems(decision.missingItemIds)
      is CompletionDecision.NonTerminalItems -> ReentryCompletion.NonTerminalItems
      is CompletionDecision.CanComplete -> ReentryCompletion.CanComplete
    }
  }
}
