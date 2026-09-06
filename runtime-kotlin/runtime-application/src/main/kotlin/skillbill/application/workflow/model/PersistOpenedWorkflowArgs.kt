package skillbill.application.workflow.model
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowId

data class PersistOpenedWorkflowArgs(
  val family: WorkflowFamily,
  val workflowId: WorkflowId,
  val effectiveSessionId: String,
  val stepId: String,
  val issueKey: IssueKey?,
  val executionIdentity: FeatureTaskExecutionIdentity?,
  val engine: WorkflowEngine,
  val database: DatabaseSessionFactory,
)
