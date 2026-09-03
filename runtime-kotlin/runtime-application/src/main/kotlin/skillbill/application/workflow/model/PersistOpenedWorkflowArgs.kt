package skillbill.application.workflow.model

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.engine.WorkflowEngine

data class PersistOpenedWorkflowArgs(
  val family: WorkflowFamily,
  val workflowId: String,
  val effectiveSessionId: String,
  val stepId: String,
  val dbOverride: String?,
  val issueKey: String?,
  val executionIdentity: FeatureTaskExecutionIdentity?,
  val engine: WorkflowEngine,
  val database: DatabaseSessionFactory,
)
