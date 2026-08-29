package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunLoop.mintAuditGapPause(
  pause: FeatureTaskRuntimeAuditGapPause,
  auditPhaseId: String,
  auditOutputArtifact: String?,
) {
  recorder.persistAuditGapPause(request.workflowId, pause, request.dbPathOverride)
  if (isGoalContinuationRun(request)) {
    goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = request.workflowId,
        workflowStatus = STATUS_PAUSED,
      ),
      dbOverride = request.dbPathOverride,
    )
  }
  val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = auditPhaseId,
    assignment = request.agentAssignment,
    invokedAgentId = request.invokedAgentId,
  )
  recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = request.workflowId,
      phaseId = auditPhaseId,
      status = STATUS_PAUSED,
      attemptCount = state.nextIteration(auditPhaseId),
      resolvedAgentId = resolvedAgent.resolvedAgentId,
      finished = false,
      blockedReason = pause.reason,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
      edgeIteration = pause.edgeIteration,
      outputArtifact = auditOutputArtifact,
    ),
    dbOverride = request.dbPathOverride,
  )
  pauseAt(auditPhaseId, pause.reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
}

internal fun FeatureTaskRuntimeRunLoop.applyAuditGapPauseDecision(
  pause: FeatureTaskRuntimeAuditGapPause,
  decision: GoalSubtaskOperatorDecision,
): String? {
  if (pause.grantConsumed) {
    return "The audit-gap pause's retry grant is already consumed; a new operator decision is required to act."
  }
  return when (decision) {
    GoalSubtaskOperatorDecision.RETRY_FIX -> {
      recorder.persistAuditGapPause(
        request.workflowId,
        pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
        request.dbPathOverride,
      )
      null
    }
    GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> {
      recorder.persistAuditGapPause(
        request.workflowId,
        pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
        request.dbPathOverride,
      )
      null
    }
    GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE ->
      "An unmet acceptance criterion cannot be accepted-and-advanced; choose retry_fix or abandon_subtask " +
        "for an audit-gap pause."
  }
}

internal fun FeatureTaskRuntimeRunLoop.remediationCheckpointBlockedReason(branch: String, error: String): String =
  "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
    "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

internal fun FeatureTaskRuntimeRunLoop.auditReviewCheckpointBlockedReason(branch: String, error: String): String =
  "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
    "before review" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to review an uncommitted final audit iteration."

internal fun FeatureTaskRuntimeRunLoop.capExhaustionReason(
  loopId: String,
  edgeIteration: Int,
  verdict: FeatureTaskRuntimeVerdict,
  unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
): String {
  if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
    return regenerationCapExhaustionReason(loopId, edgeIteration)
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
internal fun FeatureTaskRuntimeRunLoop.regenerationCapExhaustionReason(loopId: String, edgeIteration: Int): String {
  val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
    .firstOrNull { it.value == loopId }?.key
  val latest = producer?.let { producing ->
    recorder.loadQuarantinedRecords(request.workflowId, request.dbPathOverride)
      .orEmpty()
      .lastOrNull { it.producingPhaseId == producing }
  }
  val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
  return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
    "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
    "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
    "record out of band by deleting or migrating the offending row."
}

internal fun FeatureTaskRuntimeRunLoop.runPhase(args: RunPhaseArgs): PhaseOutcome {
  val phaseId = args.phaseId
  val request = args.request
  val state = args.state
  val observability = args.observability
  val specSource = args.specSource
  val reentry = args.reentry
  val phaseTokenAccumulator = args.phaseTokenAccumulator
  val declaration = phaseDeclarationForRun(phaseId, state, reentry)
  val run = buildPhaseRun(phaseId, request, declaration, specSource, reentry)
  preLaunchBlock(run, state, observability)?.let { return it }
  return runPreparedPhase(run, state, observability, phaseTokenAccumulator)
}
