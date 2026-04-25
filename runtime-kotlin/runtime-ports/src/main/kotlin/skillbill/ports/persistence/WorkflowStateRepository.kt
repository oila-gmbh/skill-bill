package skillbill.ports.persistence

import skillbill.ports.persistence.model.WorkflowStateRecord

interface WorkflowStateRepository {
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?
}
