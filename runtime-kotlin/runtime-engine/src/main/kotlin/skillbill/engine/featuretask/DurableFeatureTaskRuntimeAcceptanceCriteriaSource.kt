package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor

class DurableFeatureTaskRuntimeAcceptanceCriteriaSource(
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
) : FeatureTaskRuntimeAcceptanceCriteriaSource {
  override fun cycleRequired(workflowId: String): Boolean = true
  override fun criterionRefs(workflowId: String): List<String>? =
    runInvariantsStore.resolve(workflowId)?.let { acceptanceCriterionRefsFor(it.acceptanceCriteria.size) }
}
