package skillbill.cli.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskOutcome

internal fun FeatureTaskRuntimeRunReport.toRuntimeRunCliMap(): Map<String, Any?> = when (this) {
  is FeatureTaskRuntimeRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Blocked -> linkedMapOf(
    "status" to "blocked",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "last_incomplete_phase" to lastIncompletePhase,
    "blocked_reason" to blockedReason,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Paused -> linkedMapOf(
    "status" to "paused",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "paused_phase" to pausedPhase,
    "pause_reason" to pauseReason,
    "resumable_step" to resumableStep,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Decomposed -> linkedMapOf(
    "status" to "decomposed",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "reason" to reason,
    "completed_phases" to completedPhaseIds,
    "parent_spec_path" to parentSpecPath,
    "decomposition_manifest_path" to decompositionManifestPath,
    "subtask_spec_paths" to subtaskSpecPaths,
    "subtask_count" to subtaskSpecPaths.size,
    "guidance" to DECOMPOSE_GUIDANCE,
  )
}

internal fun Map<String, Any?>.withSubtaskOutcome(outcome: FeatureTaskRuntimeSubtaskOutcome?): Map<String, Any?> =
  if (outcome == null) {
    this
  } else {
    LinkedHashMap(this).apply {
      put(
        "subtask_outcome",
        linkedMapOf(
          "issue_key" to outcome.issueKey,
          "subtask_id" to outcome.subtaskId,
          "status" to outcome.status,
          "commit_sha" to outcome.commitSha,
          "workflow_id" to outcome.workflowId,
          "blocked_reason" to outcome.blockedReason,
          "last_resumable_step" to outcome.lastResumableStep,
          "finalizing_agent_id" to outcome.finalizingAgentId,
          "participating_agent_ids" to outcome.participatingAgentIds,
        ),
      )
    }
  }

internal fun Map<String, Any?>.runtimeRunExitCode(): Int = if (isTerminalSuccessStatus()) 0 else 1

internal fun Map<String, Any?>.isTerminalSuccessStatus(): Boolean = this["status"] in setOf("complete", "decomposed")

internal fun runtimeRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["issue_key"]}")
  appendLine("workflow_id: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"]}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("completed_phases: ${(payload["completed_phases"] as? List<*>).orEmpty().joinToString()}")
  payload["last_incomplete_phase"]?.let { appendLine("last_incomplete_phase: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
  (payload["subtask_outcome"] as? Map<*, *>)?.let { outcome -> appendSubtaskOutcome(outcome) }
  payload["reason"]?.let { appendLine("decomposition_reason: $it") }
  payload["subtask_count"]?.let { appendLine("subtask_count: $it") }
  payload["parent_spec_path"]?.let { appendLine("parent_spec_path: $it") }
  payload["decomposition_manifest_path"]?.let { appendLine("decomposition_manifest_path: $it") }
  (payload["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
  payload["guidance"]?.let { appendLine("guidance: $it") }
}

internal fun StringBuilder.appendSubtaskOutcome(outcome: Map<*, *>) {
  appendLine("subtask_outcome:")
  appendLine("  issue_key: ${outcome["issue_key"]}")
  appendLine("  subtask_id: ${outcome["subtask_id"]}")
  appendLine("  status: ${outcome["status"]}")
  appendLine("  commit_sha: ${outcome["commit_sha"] ?: "none"}")
  appendLine("  workflow_id: ${outcome["workflow_id"]}")
  appendLine("  last_resumable_step: ${outcome["last_resumable_step"]}")
  outcome["finalizing_agent_id"]?.let { appendLine("  finalizing_agent_id: $it") }
  (outcome["participating_agent_ids"] as? List<*>)?.takeIf { it.isNotEmpty() }
    ?.let { appendLine("  participating_agent_ids: ${it.joinToString()}") }
  outcome["blocked_reason"]?.let { appendLine("  blocked_reason: $it") }
}

internal const val DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."
