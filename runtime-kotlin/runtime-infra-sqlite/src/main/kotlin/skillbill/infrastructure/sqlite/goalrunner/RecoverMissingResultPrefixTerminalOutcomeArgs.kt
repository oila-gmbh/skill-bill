package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal data class RecoverMissingResultPrefixTerminalOutcomeArgs(
  val workflowStates: WorkflowStateRepository,
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val output: Map<String, Any?>,
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
)
