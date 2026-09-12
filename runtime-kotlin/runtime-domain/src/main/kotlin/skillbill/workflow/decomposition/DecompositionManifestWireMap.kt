package skillbill.workflow.decomposition

import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource

fun DecompositionManifest.toWireMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
  SharedPayloadKeys.ISSUE_KEY to issueKey,
  "feature_name" to featureName,
  "parent_spec_path" to parentSpecPath,
  SharedPayloadKeys.STATUS to status,
  "execution_model" to executionModel.wireValue,
  "base_branch" to baseBranch,
  "feature_branch" to featureBranch,
  "stack_branches" to stackBranches.map { branch ->
    linkedMapOf(
      SharedPayloadKeys.SUBTASK_ID to branch.subtaskId,
      "branch" to branch.branch,
      "base_branch" to branch.baseBranch,
    )
  },
  "current_subtask_intent" to linkedMapOf(
    SharedPayloadKeys.SUBTASK_ID to currentSubtaskIntent.subtaskId,
    "action" to currentSubtaskIntent.action,
  ),
  "subtasks" to subtasks.map { subtask ->
    linkedMapOf(
      "id" to subtask.id,
      "name" to subtask.name,
      "spec_path" to subtask.specPath,
      SharedPayloadKeys.STATUS to subtask.status,
      "branch" to subtask.branch,
      "commit_sha" to subtask.commitSha,
      SharedPayloadKeys.WORKFLOW_ID to subtask.workflowId,
      "blocked_reason" to subtask.blockedReason,
      "last_resumable_step" to subtask.lastResumableStep,
      "linear_issue_id" to subtask.linearIssueId,
      "finalizing_agent_id" to subtask.finalizingAgentId,
      "participating_agent_ids" to subtask.participatingAgentIds,
      "dependencies" to subtask.dependencies.map { dependency ->
        linkedMapOf(
          SharedPayloadKeys.SUBTASK_ID to dependency.subtaskId,
          "optional" to dependency.optional,
          "skipped" to dependency.skipped,
        )
      },
    )
  },
).apply {
  if (specSource != SpecSource.LOCAL) {
    put("spec_source", specSource.wireValue)
  }
}
