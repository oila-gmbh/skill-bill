package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement

interface FeatureTaskPhaseSettlementRepository {
  fun upsert(settlement: FeatureTaskPhaseSettlement, dbPathOverride: String? = null)

  fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    dbPathOverride: String? = null,
  ): FeatureTaskPhaseSettlement?

  fun delete(workflowId: String, phaseId: String, attempt: Int, dbPathOverride: String? = null): Boolean
}
