package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement

interface FeatureTaskPhaseSettlementRepository {
  fun upsert(settlement: FeatureTaskPhaseSettlement)

  fun find(workflowId: String, phaseId: String, attempt: Int): FeatureTaskPhaseSettlement?

  fun delete(workflowId: String, phaseId: String, attempt: Int): Boolean
}
