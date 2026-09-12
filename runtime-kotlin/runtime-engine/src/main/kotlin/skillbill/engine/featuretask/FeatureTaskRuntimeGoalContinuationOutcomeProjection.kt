package skillbill.engine.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

const val BRANCH_SETUP_AGENT_SENTINEL = "branch-setup"
const val GOAL_PLANNING_IMPORT_AGENT_SENTINEL = "goal-planning-import"

private fun String.isRuntimeAgentId(): Boolean =
  isNotBlank() && this != BRANCH_SETUP_AGENT_SENTINEL && this != GOAL_PLANNING_IMPORT_AGENT_SENTINEL

fun completedGoalContinuationOutcome(
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
      status = GoalRunnerTerminalStatus.BLOCKED,
      commitSha = null,
      workflowId = request.workflowId,
      blockedReason = "commit_push completed under suppress_pr but no commit SHA could be captured " +
        "from the phase payload or measured from git HEAD; the per-subtask commit invariant cannot " +
        "be satisfied.",
      lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
  }
}

fun completeSubtaskOutcome(
  request: FeatureTaskRuntimeRunRequest,
  context: FeatureTaskRuntimeGoalContinuationContext,
  commitSha: String?,
): FeatureTaskRuntimeSubtaskOutcome = FeatureTaskRuntimeSubtaskOutcome(
  issueKey = context.parentIssueKey,
  subtaskId = context.subtaskId,
  status = GoalRunnerTerminalStatus.COMPLETE,
  commitSha = commitSha,
  workflowId = request.workflowId,
  blockedReason = null,
  lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
)

fun measuredHeadSha(gitOperations: WorkflowGitOperations, request: FeatureTaskRuntimeRunRequest): String? {
  val result = gitOperations.headCommitSha(request.repoRoot)
  return result.value.trim().takeIf { result is WorkflowGitOperationResult.Ok && it.isNotBlank() }
}

internal data class SubtaskAgentAttribution(
  val finalizingAgentId: String?,
  val participatingAgentIds: List<String>,
)

internal fun agentAttributionFromPhaseState(
  recorder: FeatureTaskRuntimePhaseRecorder,
  workflowId: String,
): SubtaskAgentAttribution {
  val ledger = recorder.loadPhaseLedger(workflowId)
    .orEmpty()
    .sortedBy { it.sequenceNumber }
  val records = recorder.loadPhaseRecords(workflowId).orEmpty()

  return agentAttributionFromPhaseState(ledger, records)
}

internal fun agentAttributionFromPhaseState(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
): SubtaskAgentAttribution {
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

private fun terminalRecordAgentId(records: Map<String, FeatureTaskRuntimePhaseRecord>): String? {
  val realRecords = records.values.filter { it.resolvedAgentId.isRuntimeAgentId() }
  realRecords.filter { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }
    .maxByOrNull { it.finishedAt.orEmpty() }
    ?.let { return it.resolvedAgentId }
  return realRecords
    .filter { it.finishedAt != null }
    .maxByOrNull { it.finishedAt.orEmpty() }
    ?.resolvedAgentId
}

fun commitShaFromPhaseRecords(
  recorder: FeatureTaskRuntimePhaseRecorder,
  request: FeatureTaskRuntimeRunRequest,
): String? {
  val commitOutput = recorder.loadPhaseRecords(request.workflowId)
    .orEmpty()[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH]
    ?.outputArtifact
  val payload = commitOutput
    ?.let(JsonCodec::parseObjectOrNull)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
  return payload?.commitShaFromPhasePayload()
}

fun Map<String, Any?>.commitShaFromPhasePayload(): String? {
  val producedOutputs = JsonCodec.anyToStringAnyMap(this[SharedPayloadKeys.PRODUCED_OUTPUTS])
  return (this["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)
    ?: (producedOutputs?.get("commit_push_result") as? Map<*, *>)?.get("commit_sha")?.toString()
      ?.takeIf(String::isNotBlank)
    ?: producedOutputs?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)
    ?: (this["commit_sha"]?.toString()?.takeIf(String::isNotBlank))
}

fun remediationBaseCoherenceBlockedReport(
  request: FeatureTaskRuntimeRunRequest,
  operatorGuidance: String,
): FeatureTaskRuntimeRunReport.Blocked = FeatureTaskRuntimeRunReport.Blocked(
  issueKey = request.issueKey,
  workflowId = request.workflowId,
  featureSize = request.runInvariants.featureSize.name,
  lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
  blockedReason = operatorGuidance,
  completedPhaseIds = emptyList(),
  resolvedBranch = request.goalContinuation?.goalBranch,
)
