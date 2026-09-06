package skillbill.workflow.decomposition

import skillbill.agent.model.AgentId

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource

fun DecompositionManifest.toWireMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "issue_key" to issueKey.value,
  "feature_name" to featureName,
  "parent_spec_path" to parentSpecPath,
  "status" to status,
  "execution_model" to executionModel.wireValue,
  "base_branch" to baseBranch,
  "feature_branch" to featureBranch,
  "stack_branches" to stackBranches.map { branch ->
    linkedMapOf(
      "subtask_id" to branch.subtaskId.value,
      "branch" to branch.branch,
      "base_branch" to branch.baseBranch,
    )
  },
  "current_subtask_intent" to linkedMapOf(
    "subtask_id" to currentSubtaskIntent.subtaskId.value,
    "action" to currentSubtaskIntent.action,
  ),
  "subtasks" to subtasks.map { subtask ->
    linkedMapOf(
      "id" to subtask.id.value,
      "name" to subtask.name,
      "spec_path" to subtask.specPath,
      "status" to subtask.status,
      "branch" to subtask.branch,
      "commit_sha" to subtask.commitSha,
      "workflow_id" to subtask.workflowId?.value,
      "blocked_reason" to subtask.blockedReason,
      "last_resumable_step" to subtask.lastResumableStep,
      "linear_issue_id" to subtask.linearIssueId,
      "finalizing_agent_id" to subtask.finalizingAgentId?.value,
      "participating_agent_ids" to subtask.participatingAgentIds.map { it.value },
      "dependencies" to subtask.dependencies.map { dependency ->
        linkedMapOf(
          "subtask_id" to dependency.subtaskId.value,
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
