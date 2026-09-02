package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

fun FeatureTaskRuntimeRunLoopPlanningBranch.pauseAt(
  runLoop: FeatureTaskRuntimeRunLoop,
  phaseId: String,
  reason: String,
  resumableStep: String,
) {
  runLoop.session.paused = FeatureTaskRuntimeRunReport.Paused(
    issueKey = runLoop.request.issueKey,
    workflowId = runLoop.request.workflowId,
    featureSize = runLoop.request.runInvariants.featureSize.name,
    pausedPhase = phaseId,
    pauseReason = reason,
    resumableStep = resumableStep,
    completedPhaseIds = runLoop.state.completedPhaseIds(),
    resolvedBranch = runLoop.session.resolvedBranch,
  )
}

fun FeatureTaskRuntimeRunLoopPlanningBranch.mintAuditGapPause(
  runLoop: FeatureTaskRuntimeRunLoop,
  pause: FeatureTaskRuntimeAuditGapPause,
  auditPhaseId: String,
  auditOutputArtifact: String?,
) {
  runLoop.recorder.persistAuditGapPause(runLoop.request.workflowId, pause, runLoop.request.dbPathOverride)
  if (isGoalContinuationRun(runLoop.request)) {
    runLoop.goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = runLoop.request.workflowId,
        workflowStatus = STATUS_PAUSED,
      ),
      dbOverride = runLoop.request.dbPathOverride,
    )
  }
  val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = auditPhaseId,
    assignment = runLoop.request.agentAssignment,
    invokedAgentId = runLoop.request.invokedAgentId,
  )
  runLoop.recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = runLoop.request.workflowId,
      phaseId = auditPhaseId,
      status = STATUS_PAUSED,
      attemptCount = runLoop.state.nextIteration(auditPhaseId),
      resolvedAgentId = resolvedAgent.resolvedAgentId,
      finished = false,
      blockedReason = pause.reason,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
      edgeIteration = pause.edgeIteration,
      outputArtifact = auditOutputArtifact,
    ),
    dbOverride = runLoop.request.dbPathOverride,
  )
  pauseAt(runLoop, auditPhaseId, pause.reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
}

fun FeatureTaskRuntimeRunLoopPlanningBranch.applyAuditGapPauseDecision(
  runLoop: FeatureTaskRuntimeRunLoop,
  pause: FeatureTaskRuntimeAuditGapPause,
  decision: GoalSubtaskOperatorDecision,
): String? {
  if (pause.grantConsumed) {
    return "The audit-gap pause's retry grant is already consumed; a new operator decision is required to act."
  }
  return when (decision) {
    GoalSubtaskOperatorDecision.RETRY_FIX -> {
      runLoop.recorder.persistAuditGapPause(
        runLoop.request.workflowId,
        pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
        runLoop.request.dbPathOverride,
      )
      null
    }
    GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> {
      runLoop.recorder.persistAuditGapPause(
        runLoop.request.workflowId,
        pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
        runLoop.request.dbPathOverride,
      )
      null
    }
    GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE ->
      "An unmet acceptance criterion cannot be accepted-and-advanced; choose retry_fix or abandon_subtask " +
        "for an audit-gap pause."
  }
}

fun FeatureTaskRuntimeRunLoopPlanningBranch.remediationCheckpointBlockedReason(branch: String, error: String): String =
  "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
    "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

fun FeatureTaskRuntimeRunLoopPlanningBranch.auditReviewCheckpointBlockedReason(branch: String, error: String): String =
  "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
    "before review" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to review an uncommitted final audit iteration."

fun FeatureTaskRuntimeRunLoopPlanningBranch.capExhaustionReason(
  runLoop: FeatureTaskRuntimeRunLoop,
  loopId: String,
  edgeIteration: Int,
  verdict: FeatureTaskRuntimeVerdict,
  unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
): String {
  if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
    return regenerationCapExhaustionReason(runLoop, loopId, edgeIteration)
  }
  val findingsSuffix = if (unresolvedFindings.isEmpty()) {
    ""
  } else {
    " Unresolved findings: " +
      unresolvedFindings.joinToString("; ") { "[${it.severity.wireValue}] ${it.message}" } + "."
  }
  return "Backward-edge loop '$loopId' exhausted its per-edge cap after $edgeIteration iteration(s) with the " +
    "verdict '${verdict.wireValue}' still unresolved; the run blocks rather than re-entering past the cap." +
    findingsSuffix
}

// SKILL-140: AC-004 cap-exhaustion reason for a regeneration loop, naming the quarantined record, the
// producing phase, and the attempt count. Shared by the in-run transition block and the pre-launch
// resume cap guard, so both surface the same actionable message.
