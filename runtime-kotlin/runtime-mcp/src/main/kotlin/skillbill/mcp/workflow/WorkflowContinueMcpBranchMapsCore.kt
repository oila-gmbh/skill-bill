package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.contracts.SharedPayloadKeys

internal fun WorkflowContinueResult.Standard.toStandardMcpMap(): Map<String, Any?> =
  standardMcpContinueMap(view, dbPath, decompositionExtras = emptyMap())

internal fun WorkflowContinueResult.UnknownWorkflow.toUnknownWorkflowMcpMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.STATUS to "error",
  SharedPayloadKeys.WORKFLOW_ID to workflowId,
  "error" to "Unknown workflow_id '$workflowId'.",
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.Error.toErrorMcpMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.STATUS to "error",
  SharedPayloadKeys.WORKFLOW_ID to workflowId,
  "error" to error,
  "db_path" to dbPath,
)
