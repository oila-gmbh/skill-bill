package skillbill.cli.workflow

import skillbill.application.workflow.model.GoalContinuationOutcome
import skillbill.application.workflow.model.WorkflowContinueResult

internal fun WorkflowContinueResult.DecompositionStandard.toDecompositionStandardCliMap(): Map<String, Any?> =
  standardContinueMap(
    view = view,
    dbPath = dbPath,
    decompositionExtras = linkedMapOf(
      "issue_key" to (outcome?.issueKey ?: issueKey),
      "decomposition_subtask_id" to decompositionSubtaskId,
      "decomposition_subtask_spec_path" to decompositionSubtaskSpecPath,
      "goal_continuation_outcome" to outcome.toWireMap(),
    ),
  )

internal fun WorkflowContinueResult.DecompositionMissingSubtaskWorkflow.toDecompositionMissingSubtaskWorkflowCliMap():
  Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "subtask_id" to subtaskId,
  "blocked_reason" to blockedReason,
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionBlockedSubtask.toDecompositionBlockedSubtaskCliMap():
  Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "workflow_id" to workflowId,
  "issue_key" to issueKey,
  "decomposition_subtask_id" to subtaskId,
  "decomposition_subtask_spec_path" to subtaskSpecPath,
  "blocked_reason" to blockedReason,
  "error" to blockedReason,
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionBlockedBranchStart.toDecompositionBlockedBranchStartCliMap():
  Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "workflow_id" to workflowId,
  "issue_key" to issueKey,
  "error" to blockedReason,
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionDone.toDecompositionDoneCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "continue_status" to "done",
  "workflow_id" to workflowId,
  "issue_key" to issueKey,
  "decomposition_status" to decompositionStatus,
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionSubtaskOutcome.toDecompositionSubtaskOutcomeCliMap():
  Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "continue_status" to "done",
  "workflow_id" to workflowId,
  "issue_key" to issueKey,
  "decomposition_subtask_id" to subtaskId,
  "decomposition_subtask_spec_path" to subtaskSpecPath,
  "goal_continuation_outcome" to outcome.toWireMap(),
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionBlockedGit.toDecompositionBlockedGitCliMap(): Map<String, Any?> =
  linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "blocked_reason" to blockedReason,
    "error" to blockedReason,
    "db_path" to dbPath,
  )

internal fun GoalContinuationOutcome?.toWireMap(): Map<String, Any?> = this?.let { outcome ->
  linkedMapOf(
    "issue_key" to outcome.issueKey,
    "subtask_id" to outcome.subtaskId,
    "status" to outcome.status,
    "commit_sha" to outcome.commitSha,
    "workflow_id" to outcome.workflowId,
    "blocked_reason" to outcome.blockedReason,
    "last_resumable_step" to outcome.lastResumableStep,
  )
}.orEmpty()
