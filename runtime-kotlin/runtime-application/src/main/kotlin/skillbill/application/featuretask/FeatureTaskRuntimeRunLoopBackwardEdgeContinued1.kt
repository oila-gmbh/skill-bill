package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

fun FeatureTaskRuntimeRunLoopBackwardEdge.blocksWhenCapExhausted(
  edge: FeatureTaskRuntimeBackwardEdge,
  iteration: Int,
): Boolean = edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
  edge.perEdgeCap?.let { iteration >= it } == true

fun FeatureTaskRuntimeRunLoopBackwardEdge.runPhaseFor(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
  val briefingReentry = runLoop.session.pendingReentry?.takeIf { it.phaseId == phaseId }
  if (briefingReentry != null) runLoop.session.pendingReentry = null
  val reentry = briefingReentry ?: runLoop.session.activeReentry?.takeIf { active ->
    runLoop.transitions.backwardEdges
      .firstOrNull { it.loopId == active.loopId }
      ?.let { edge ->
        phaseId in runLoop.collaborators.transitions.spanBetween(
          runLoop,
          edge.destinationPhaseId,
          edge.fromPhaseId,
        )
      } == true
  }?.copy(phaseId = phaseId, reentryGapCriteria = emptyList())
  val outcome = runLoop.collaborators.planningBranch.runPhase(
    runLoop,
    RunPhaseArgs(
      phaseId = phaseId,
      request = runLoop.request,
      state = runLoop.state,
      observability = runLoop.observability,
      specSource = runLoop.specSource,
      reentry = reentry,
      phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
    ),
  )
  outcome.regenerationTargetPhaseId?.let {
    // The launch seam quarantined an upstream record and requested regeneration. Do not record this
    // consumer as completed; signal advance() to settle it with the RECORD_REJECTED verdict so the
    // transition machinery re-enters the producer.
    runLoop.session.recordRejectionSettlementPending = true
    return null
  }
  outcome.pausedReason?.let { return it }
  return outcome.blockedReason ?: run {
    val completedOutput = requireNotNull(outcome.completedOutput)
    runLoop.state.recordCompleted(completedOutput)
    if (runLoop.session.operatorBlockRetry?.phaseId == phaseId) runLoop.session.operatorBlockRetryCompleted = true
    applyPlanningStop(runLoop, phaseId, completedOutput)
  }
}

// Only the edge destination gets a LOOP_EDGE ledger entry carrying `verifier_reentry`, so only the
// destination may defer its start kind to that entry. Every other phase in the reopened span still
// owns its own start kind.
internal fun FeatureTaskRuntimeRunLoopBackwardEdge.isLoopDestination(
  runLoop: FeatureTaskRuntimeRunLoop,
  reentry: PendingReentry,
): Boolean =
  runLoop.transitions.backwardEdges.firstOrNull { it.loopId == reentry.loopId }?.destinationPhaseId == reentry.phaseId

internal fun FeatureTaskRuntimeRunLoopBackwardEdge.blockInvalidAuditGapRecovery(
  runLoop: FeatureTaskRuntimeRunLoop,
  reentry: PendingReentry,
  reason: String,
) {
  val phaseId = reentry.phaseId
  val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = runLoop.request.agentAssignment,
    invokedAgentId = runLoop.request.invokedAgentId,
  ).resolvedAgentId
  val attempt = runLoop.state.nextIteration(phaseId)
  val previous = runLoop.state.recordFor(phaseId)
  runLoop.recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = runLoop.request.workflowId,
      phaseId = phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attempt,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = previous?.outputArtifact,
      rejectedOutput = previous?.rejectedOutput,
      blockedReason = reason,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      loopId = reentry.loopId,
      edgeIteration = reentry.edgeIteration,
    ),
    runLoop.request.dbPathOverride,
  )
  runLoop.observability.blocked(phaseId, resolvedAgentId, attempt, reason)
  runLoop.collaborators.planningBranch.blockAt(runLoop, phaseId, reason)
}

fun FeatureTaskRuntimeRunLoopBackwardEdge.applyPlanningStop(
  runLoop: FeatureTaskRuntimeRunLoop,
  phaseId: String,
  planOutput: FeatureTaskRuntimePhaseOutput,
): String? {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
    return null
  }
  return when (val decision = resolvePlanningStop(runLoop, planOutput)) {
    is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
    is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
      runLoop.session.decomposed = decision.report
      null
    }
    is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
      persistPlanningStopBlock(runLoop, phaseId, decision.reason)
      decision.reason
    }
  }
}

fun FeatureTaskRuntimeRunLoopBackwardEdge.resolvePlanningStop(
  runLoop: FeatureTaskRuntimeRunLoop,
  planOutput: FeatureTaskRuntimePhaseOutput,
): FeatureTaskRuntimePlanningStopDecision = runLoop.planningStopper.resolve(
  request = runLoop.request,
  completedOutput = planOutput,
  completedPhaseIds = runLoop.state.completedPhaseIds(),
  resolvedBranch = runLoop.session.resolvedBranch,
  specSource = runLoop.specSource,
)
