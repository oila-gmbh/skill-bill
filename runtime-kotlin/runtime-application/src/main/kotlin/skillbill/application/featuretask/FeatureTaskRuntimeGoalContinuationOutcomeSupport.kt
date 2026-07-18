package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

internal const val BRANCH_SETUP_AGENT_SENTINEL = "branch-setup"
internal const val GOAL_PLANNING_IMPORT_AGENT_SENTINEL = "goal-planning-import"

private fun String.isRuntimeAgentId(): Boolean =
  isNotBlank() && this != BRANCH_SETUP_AGENT_SENTINEL && this != GOAL_PLANNING_IMPORT_AGENT_SENTINEL

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

/**
 * The agent-attribution rollup derived from the child's existing phase ledger / phase records.
 * [finalizingAgentId] is the resolved agent of the terminal COMPLETE/BLOCKED action; [participatingAgentIds]
 * is the order-stable distinct set of resolved agents that actually executed a phase.
 */
internal data class SubtaskAgentAttribution(
  val finalizingAgentId: String?,
  val participatingAgentIds: List<String>,
)

// Effect-free rollup over the durable phase ledger + per-phase records. Re-resolves nothing and mints no
// value: every agent id read here is a resolvedAgentId the runtime already persisted. Participants are
// first-seen distinct resolved agents walking the ordered ledger, then any phase-record agent the ledger
// pruning may have dropped. The finalizer is the resolved agent of the last COMPLETE/BLOCKED ledger entry,
// falling back to the terminal phase record (blocked record, else the last-finished phase) when that ledger
// entry has been pruned.
internal fun agentAttributionFromPhaseState(
  recorder: FeatureTaskRuntimePhaseRecorder,
  workflowId: String,
  dbOverride: String? = null,
): SubtaskAgentAttribution {
  val ledger = recorder.loadPhaseLedger(workflowId, dbOverride)
    .orEmpty()
    .sortedBy { it.sequenceNumber }
  val records = recorder.loadPhaseRecords(workflowId, dbOverride).orEmpty()

  val participating = LinkedHashSet<String>()
  ledger.forEach { entry ->
    entry.resolvedAgentId?.takeIf(String::isRuntimeAgentId)
      ?.let(participating::add)
  }
  records.values.forEach { record ->
    record.resolvedAgentId.takeIf(String::isRuntimeAgentId)
      ?.let(participating::add)
  }

  val finalizingFromLedger = ledger.lastOrNull { entry ->
    (
      entry.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE ||
        entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      ) &&
      entry.resolvedAgentId?.isRuntimeAgentId() == true
  }?.resolvedAgentId
  val finalizingAgentId = finalizingFromLedger ?: terminalRecordAgentId(records)

  return SubtaskAgentAttribution(
    finalizingAgentId = finalizingAgentId,
    participatingAgentIds = participating.toList(),
  )
}

// Fallback finalizer when the terminal ledger entry was pruned: prefer the blocked terminal record with the
// greatest finishedAt (deterministic on multiple blocked), else the last-finished phase record by the same
// ordering. Branch-setup-sentinel records are excluded: they carry no real agent id.
private fun terminalRecordAgentId(records: Map<String, FeatureTaskRuntimePhaseRecord>): String? {
  val realRecords = records.values.filter { it.resolvedAgentId.isRuntimeAgentId() }
  realRecords.filter { it.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED }
    .maxByOrNull { it.finishedAt.orEmpty() }
    ?.let { return it.resolvedAgentId }
  return realRecords
    .filter { it.finishedAt != null }
    .maxByOrNull { it.finishedAt.orEmpty() }
    ?.resolvedAgentId
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
