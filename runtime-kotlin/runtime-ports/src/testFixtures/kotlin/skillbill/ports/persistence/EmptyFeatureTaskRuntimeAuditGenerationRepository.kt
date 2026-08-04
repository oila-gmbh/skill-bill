package skillbill.ports.persistence

import skillbill.ports.persistence.model.FeatureTaskRuntimeAuditGenerationRow

/**
 * A readable audit-generation history with nothing in it — the state of every workflow before its first
 * audit settles. Writes loud-fail rather than resolving to a no-op: a fake that accepted an append and
 * forgot it would let a projection test assert convergence counters no durable record supports.
 */
object EmptyFeatureTaskRuntimeAuditGenerationRepository : FeatureTaskRuntimeAuditGenerationRepository {
  override fun append(row: FeatureTaskRuntimeAuditGenerationRow): Nothing =
    error("This audit-generation fixture is read-only; use a recording fake to exercise appends.")

  override fun listOrdered(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> = emptyList()

  override fun quarantineAll(workflowId: String): Int = 0
}
