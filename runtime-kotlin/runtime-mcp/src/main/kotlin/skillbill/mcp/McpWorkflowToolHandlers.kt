package skillbill.mcp

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest

internal fun workflowOpen(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.open(
  kind = kind,
  sessionId = arguments.string("session_id"),
  currentStepId = arguments.optionalString("current_step_id"),
  context = context,
)

internal fun workflowUpdate(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.update(
  kind = kind,
  request =
  WorkflowUpdateRequest(
    workflowId = arguments.string("workflow_id"),
    workflowStatus = arguments.string("workflow_status"),
    currentStepId = arguments.string("current_step_id"),
    stepUpdates = arguments.optionalListMap("step_updates"),
    artifactsPatch = arguments.optionalMap("artifacts_patch"),
    sessionId = arguments.string("session_id"),
  ),
  context = context,
)

internal fun workflowGet(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.get(kind, arguments.string("workflow_id"), context)

internal fun workflowList(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.list(kind, limit = arguments.int("limit", default = 20), context = context)

internal fun workflowResume(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.resume(kind, arguments.string("workflow_id"), context)

internal fun workflowContinue(
  kind: WorkflowFamilyKind,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> = McpWorkflowRuntime.continueWorkflow(kind, arguments.string("workflow_id"), context)
