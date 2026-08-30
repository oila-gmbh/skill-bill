package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowContinueResult

internal fun WorkflowContinueResult.Standard.toStandardMcpMap(): Map<String, Any?> =
  standardMcpContinueMap(view, dbPath, decompositionExtras = emptyMap())

internal fun WorkflowContinueResult.UnknownWorkflow.toUnknownWorkflowMcpMap(): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "workflow_id" to workflowId,
  "error" to "Unknown workflow_id '$workflowId'.",
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.Error.toErrorMcpMap(): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "workflow_id" to workflowId,
  "error" to error,
  "db_path" to dbPath,
)
