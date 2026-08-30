package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowContinueResult

internal fun WorkflowContinueResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowContinueResult.Standard -> toStandardMcpMap()
  is WorkflowContinueResult.DecompositionStandard -> toDecompositionStandardMcpMap()
  is WorkflowContinueResult.UnknownWorkflow -> toUnknownWorkflowMcpMap()
  is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow -> toDecompositionMissingSubtaskWorkflowMcpMap()
  is WorkflowContinueResult.DecompositionBlockedSubtask -> toDecompositionBlockedSubtaskMcpMap()
  is WorkflowContinueResult.DecompositionBlockedBranchStart -> toDecompositionBlockedBranchStartMcpMap()
  is WorkflowContinueResult.DecompositionDone -> toDecompositionDoneMcpMap()
  is WorkflowContinueResult.DecompositionSubtaskOutcome -> toDecompositionSubtaskOutcomeMcpMap()
  is WorkflowContinueResult.DecompositionBlockedGit -> toDecompositionBlockedGitMcpMap()
  is WorkflowContinueResult.Error -> toErrorMcpMap()
}
