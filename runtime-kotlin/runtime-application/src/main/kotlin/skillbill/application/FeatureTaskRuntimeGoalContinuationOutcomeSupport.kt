package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

// Capture-at-source for a completed goal-continuation run (SKILL-68). Under suppress_pr the
// per-subtask commit invariant requires a SHA: take it from the phase payload, else measure git
// HEAD once. Complete iff a non-blank SHA is present; otherwise block with an explicit reason and
// last_resumable_step=commit_push rather than recording a SHA-less `complete`. A non-suppress_pr
// continuation keeps the prior behavior (payload SHA, no measured-HEAD fallback).
internal fun completedGoalContinuationOutcome(
  recorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  context: FeatureTaskRuntimeGoalContinuationContext,
): FeatureTaskRuntimeSubtaskOutcome {
  val payloadSha = commitShaFromPhaseRecords(recorder, request)
  if (!context.suppressPr) {
    return completeSubtaskOutcome(request, context, payloadSha)
  }
  val resolvedSha = payloadSha ?: measuredHeadSha(gitOperations, request)
  return if (resolvedSha != null) {
    completeSubtaskOutcome(request, context, resolvedSha)
  } else {
    FeatureTaskRuntimeSubtaskOutcome(
      issueKey = context.parentIssueKey,
      subtaskId = context.subtaskId,
      status = "blocked",
      commitSha = null,
      workflowId = request.workflowId,
      blockedReason = "commit_push completed under suppress_pr but no commit SHA could be captured " +
        "from the phase payload or measured from git HEAD; the per-subtask commit invariant cannot " +
        "be satisfied.",
      lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
  }
}

internal fun completeSubtaskOutcome(
  request: FeatureTaskRuntimeRunRequest,
  context: FeatureTaskRuntimeGoalContinuationContext,
  commitSha: String?,
): FeatureTaskRuntimeSubtaskOutcome = FeatureTaskRuntimeSubtaskOutcome(
  issueKey = context.parentIssueKey,
  subtaskId = context.subtaskId,
  status = "complete",
  commitSha = commitSha,
  workflowId = request.workflowId,
  blockedReason = null,
  lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
)

// Single git-HEAD read through the domain-owned port; the trimmed value is returned only when the
// result is ok and non-blank (mirrors GoalRunnerWorkflowStores.measuredCommitSha).
internal fun measuredHeadSha(gitOperations: WorkflowGitOperations, request: FeatureTaskRuntimeRunRequest): String? {
  val result = gitOperations.headCommitSha(request.repoRoot)
  return result.value.trim().takeIf { result.ok && it.isNotBlank() }
}

internal fun commitShaFromPhaseRecords(
  recorder: FeatureTaskRuntimePhaseRecorder,
  request: FeatureTaskRuntimeRunRequest,
): String? {
  val commitOutput = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
    .orEmpty()[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH]
    ?.outputArtifact
  val payload = commitOutput
    ?.let(JsonSupport::parseObjectOrNull)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
  return payload?.commitShaFromPhasePayload()
}

internal fun Map<String, Any?>.commitShaFromPhasePayload(): String? {
  val producedOutputs = JsonSupport.anyToStringAnyMap(this["produced_outputs"])
  return (this["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)
    ?: (producedOutputs?.get("commit_push_result") as? Map<*, *>)?.get("commit_sha")?.toString()
      ?.takeIf(String::isNotBlank)
    ?: producedOutputs?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)
    ?: (this["commit_sha"]?.toString()?.takeIf(String::isNotBlank))
}
