package skillbill.ports.featuretask.model

import skillbill.workflow.taskruntime.model.AuditRepairCycle

data class AuditRepairStatusSnapshot(val artifactsJson: String, val cycle: AuditRepairCycle?)
