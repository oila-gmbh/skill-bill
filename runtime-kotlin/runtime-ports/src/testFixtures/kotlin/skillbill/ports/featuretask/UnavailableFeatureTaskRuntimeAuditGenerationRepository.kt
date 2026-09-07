package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskRuntimeAuditGenerationRow

object UnavailableFeatureTaskRuntimeAuditGenerationRepository : FeatureTaskRuntimeAuditGenerationRepository {
  private const val REASON: String =
    "Feature-task-runtime audit-generation persistence is not available on this unit of work."

  override fun append(row: FeatureTaskRuntimeAuditGenerationRow): Nothing = error(REASON)

  override fun listOrdered(workflowId: String): Nothing = error(REASON)

  override fun quarantineAll(workflowId: String): Nothing = error(REASON)
}
