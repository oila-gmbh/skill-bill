package skillbill.ports.persistence

data class WorkflowStateRecord(
  val workflowId: String,
  val sessionId: String,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: String,
  val currentStepId: String,
  val stepsJson: String,
  val artifactsJson: String,
  val startedAt: String?,
  val updatedAt: String?,
  val finishedAt: String?,
)

interface WorkflowStateRepository {
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?
}
