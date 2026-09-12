package skillbill.cli.workflow

import skillbill.contracts.SharedPayloadKeys

import skillbill.application.workflow.model.GoalContinuationOutcome
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.workflow.model.WorkflowContinueStatus

internal fun WorkflowContinueResult.DecompositionStandard.toDecompositionStandardCliMap(): Map<String, Any?> =
  standardContinueMap(
    view = view,
    dbPath = dbPath,
    decompositionExtras = linkedMapOf(
      SharedPayloadKeys.ISSUE_KEY to (outcome?.issueKey ?: issueKey),
      "decomposition_subtask_id" to decompositionSubtaskId,
      "decomposition_subtask_spec_path" to decompositionSubtaskSpecPath,
      "goal_continuation_outcome" to outcome.toWireMap(),
    ),
  )

internal fun WorkflowContinueResult.DecompositionMissingSubtaskWorkflow.toDecompositionMissingSubtaskWorkflowCliMap():
  Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    "continue_status" to WorkflowContinueStatus.BLOCKED.wireValue,
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    "blocked_reason" to blockedReason,
    "db_path" to dbPath,
  )

internal fun WorkflowContinueResult.DecompositionBlockedSubtask.toDecompositionBlockedSubtaskCliMap():
  Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    "continue_status" to WorkflowContinueStatus.BLOCKED.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "decomposition_subtask_id" to subtaskId,
    "decomposition_subtask_spec_path" to subtaskSpecPath,
    "blocked_reason" to blockedReason,
    "error" to blockedReason,
    "db_path" to dbPath,
  )

internal fun WorkflowContinueResult.DecompositionBlockedBranchStart.toDecompositionBlockedBranchStartCliMap():
  Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    "continue_status" to WorkflowContinueStatus.BLOCKED.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "error" to blockedReason,
    "db_path" to dbPath,
  )

internal fun WorkflowContinueResult.DecompositionDone.toDecompositionDoneCliMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.STATUS to "ok",
  "continue_status" to WorkflowContinueStatus.DONE.wireValue,
  SharedPayloadKeys.WORKFLOW_ID to workflowId,
  SharedPayloadKeys.ISSUE_KEY to issueKey,
  "decomposition_status" to decompositionStatus,
  "db_path" to dbPath,
)

internal fun WorkflowContinueResult.DecompositionSubtaskOutcome.toDecompositionSubtaskOutcomeCliMap():
  Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "ok",
    "continue_status" to WorkflowContinueStatus.DONE.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "decomposition_subtask_id" to subtaskId,
    "decomposition_subtask_spec_path" to subtaskSpecPath,
    "goal_continuation_outcome" to outcome.toWireMap(),
    "db_path" to dbPath,
  )

internal fun WorkflowContinueResult.DecompositionBlockedGit.toDecompositionBlockedGitCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    "continue_status" to WorkflowContinueStatus.BLOCKED.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "blocked_reason" to blockedReason,
    "error" to blockedReason,
    "db_path" to dbPath,
  )

internal fun GoalContinuationOutcome?.toWireMap(): Map<String, Any?> = this?.let { outcome ->
  linkedMapOf(
    SharedPayloadKeys.ISSUE_KEY to outcome.issueKey,
    SharedPayloadKeys.SUBTASK_ID to outcome.subtaskId,
    SharedPayloadKeys.STATUS to outcome.status,
    "commit_sha" to outcome.commitSha,
    SharedPayloadKeys.WORKFLOW_ID to outcome.workflowId,
    "blocked_reason" to outcome.blockedReason,
    "last_resumable_step" to outcome.lastResumableStep,
  )
}.orEmpty()
