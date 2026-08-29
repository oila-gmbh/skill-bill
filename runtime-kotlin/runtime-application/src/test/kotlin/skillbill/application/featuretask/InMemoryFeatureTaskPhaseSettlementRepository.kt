package skillbill.application.featuretask

import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import java.util.concurrent.ConcurrentHashMap

class InMemoryFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  private val rows = ConcurrentHashMap<String, FeatureTaskPhaseSettlement>()

  override fun upsert(settlement: FeatureTaskPhaseSettlement, dbPathOverride: String?) {
    rows[key(settlement.workflowId, settlement.phaseId, settlement.attempt)] = settlement
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    dbPathOverride: String?,
  ): FeatureTaskPhaseSettlement? = rows[key(workflowId, phaseId, attempt)]

  override fun delete(workflowId: String, phaseId: String, attempt: Int, dbPathOverride: String?): Boolean =
    rows.remove(key(workflowId, phaseId, attempt)) != null

  private fun key(workflowId: String, phaseId: String, attempt: Int): String = "$workflowId::$phaseId::$attempt"
}
