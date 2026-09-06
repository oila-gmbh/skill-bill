package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.engine.model.WorkflowId

interface FeatureTaskPhaseSettlementRepository {
  fun upsert(settlement: FeatureTaskPhaseSettlement)

  fun find(workflowId: WorkflowId, phaseId: String, attempt: Int): FeatureTaskPhaseSettlement?

  fun delete(workflowId: WorkflowId, phaseId: String, attempt: Int): Boolean
}
