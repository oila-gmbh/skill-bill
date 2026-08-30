package skillbill.cli.workflow

import skillbill.application.workflow.model.WorkflowContinueResult

internal fun WorkflowContinueResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowContinueResult.Standard -> toStandardCliMap()
  is WorkflowContinueResult.DecompositionStandard -> toDecompositionStandardCliMap()
  is WorkflowContinueResult.UnknownWorkflow -> toUnknownWorkflowCliMap()
  is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow -> toDecompositionMissingSubtaskWorkflowCliMap()
  is WorkflowContinueResult.DecompositionBlockedSubtask -> toDecompositionBlockedSubtaskCliMap()
  is WorkflowContinueResult.DecompositionBlockedBranchStart -> toDecompositionBlockedBranchStartCliMap()
  is WorkflowContinueResult.DecompositionDone -> toDecompositionDoneCliMap()
  is WorkflowContinueResult.DecompositionSubtaskOutcome -> toDecompositionSubtaskOutcomeCliMap()
  is WorkflowContinueResult.DecompositionBlockedGit -> toDecompositionBlockedGitCliMap()
  is WorkflowContinueResult.Error -> toErrorCliMap()
}

internal fun WorkflowContinueResult.Standard.toStandardCliMap(): Map<String, Any?> =
  standardContinueMap(view, dbPath, decompositionExtras = emptyMap())

internal fun WorkflowContinueResult.UnknownWorkflow.toUnknownWorkflowCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "workflow_id" to workflowId,
  "error" to "Unknown workflow_id '$workflowId'.",
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.Error.toErrorCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "workflow_id" to workflowId,
  "error" to error,
  "db_path" to dbPath,
)
