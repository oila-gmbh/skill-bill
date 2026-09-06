package skillbill.ports.featuretask

import WorkflowId
import skillbill.ports.featuretask.model.FeatureTaskRuntimeAuditGenerationRow

object UnavailableFeatureTaskRuntimeAuditGenerationRepository : FeatureTaskRuntimeAuditGenerationRepository {
  private const val REASON: String =
    "Feature-task-runtime audit-generation persistence is not available on this unit of work."

  override fun append(row: FeatureTaskRuntimeAuditGenerationRow): Nothing = error(REASON)

  override fun listOrdered(workflowId: WorkflowId): Nothing = error(REASON)

  override fun quarantineAll(workflowId: WorkflowId): Nothing = error(REASON)
}
