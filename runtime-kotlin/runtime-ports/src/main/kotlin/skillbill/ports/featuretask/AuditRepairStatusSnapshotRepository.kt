package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.AuditRepairStatusSnapshot

interface AuditRepairStatusSnapshotRepository {
  fun statusSnapshot(workflowId: String): AuditRepairStatusSnapshot?
}
