package skillbill.ports.persistence

import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord

interface WorkflowStateRepository {
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?

  fun listFeatureImplementWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun listFeatureVerifyWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureImplementWorkflow(): WorkflowStateRecord?

  fun latestFeatureVerifyWorkflow(): WorkflowStateRecord?

  fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary?

  fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary?
}
