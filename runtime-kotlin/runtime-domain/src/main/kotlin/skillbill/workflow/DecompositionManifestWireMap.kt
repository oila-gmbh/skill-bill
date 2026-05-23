package skillbill.workflow

import skillbill.workflow.model.DecompositionManifest

fun DecompositionManifest.toWireMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "issue_key" to issueKey,
  "feature_name" to featureName,
  "parent_spec_path" to parentSpecPath,
  "status" to status,
  "execution_model" to executionModel.wireValue,
  "base_branch" to baseBranch,
  "feature_branch" to featureBranch,
  "stack_branches" to stackBranches.map { branch ->
    linkedMapOf(
      "subtask_id" to branch.subtaskId,
      "branch" to branch.branch,
      "base_branch" to branch.baseBranch,
    )
  },
  "current_subtask_intent" to linkedMapOf(
    "subtask_id" to currentSubtaskIntent.subtaskId,
    "action" to currentSubtaskIntent.action,
  ),
  "subtasks" to subtasks.map { subtask ->
    linkedMapOf(
      "id" to subtask.id,
      "name" to subtask.name,
      "spec_path" to subtask.specPath,
      "status" to subtask.status,
      "branch" to subtask.branch,
      "commit_sha" to subtask.commitSha,
      "workflow_id" to subtask.workflowId,
      "review_result" to subtask.reviewResult,
      "audit_result" to subtask.auditResult,
      "validation_result" to subtask.validationResult,
      "blocked_reason" to subtask.blockedReason,
      "last_resumable_step" to subtask.lastResumableStep,
      "dependencies" to subtask.dependencies.map { dependency ->
        linkedMapOf(
          "subtask_id" to dependency.subtaskId,
          "optional" to dependency.optional,
          "skipped" to dependency.skipped,
        )
      },
    )
  },
)
