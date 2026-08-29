package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunLoop.resumeInFlightReviewFix(edge: FeatureTaskRuntimeBackwardEdge): String? {
  if (
    edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID ||
    state.isLoopLiveClaimed(edge.loopId) ||
    state.isComplete(edge.destinationPhaseId)
  ) {
    return null
  }
  val destinationRecord = state.recordFor(edge.destinationPhaseId)
    ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == state.edgeIterationCount(edge.loopId) }
    ?: return null
  val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
  state.reopenForReentry(edge.fromPhaseId)
  state.recordEdgeIteration(edge.loopId, edgeIteration)
  pendingReentry = PendingReentry(
    phaseId = edge.destinationPhaseId,
    loopId = edge.loopId,
    edgeIteration = edgeIteration,
    drivingVerdict = edge.triggeringVerdict,
    expectedRepositoryCheckpoint = reviewedCheckpointFingerprint(),
  )
  activeReentry = pendingReentry
  return edge.destinationPhaseId
}

internal fun FeatureTaskRuntimeRunLoop.recordBackwardEdge(
  edge: FeatureTaskRuntimeBackwardEdge,
  destinationPhaseId: String,
  loopId: String,
  edgeIteration: Int,
  verdict: FeatureTaskRuntimeVerdict,
) {
  val reopenedSpan = spanBetween(destinationPhaseId, edge.fromPhaseId)
  reopenedSpan.forEach(state::reopenForReentry)
  if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
    // Invalidate the quarantined producer's settled completion so its rejected record is no longer
    // selected by the handoff contract; the regenerated higher-iteration output supersedes it. In
    // memory the stale output is dropped from resolution; durably the record returns to running so a
    // resume relaunches the producer rather than re-consuming the rejected record.
    state.invalidateProducerOutput(destinationPhaseId)
    recorder.invalidateQuarantinedProducerRecord(
      request.workflowId,
      destinationPhaseId,
      loopId,
      edgeIteration,
      request.dbPathOverride,
    )
  }
  state.recordEdgeIteration(loopId, edgeIteration)
  pendingReentry = PendingReentry(
    destinationPhaseId,
    loopId,
    edgeIteration,
    verdict,
    emptyList(),
    if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
      reviewedCheckpointFingerprint()
    } else {
      null
    },
  )
  activeReentry = pendingReentry
  observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
  warnOnThresholdCrossing(edge, edgeIteration)
}

/**
 * Advisory crossing warning for a semantic remediation loop that just passed its declared warning
 * threshold. It is emitted strictly after the durable re-entry ledger row for this iteration, so a
 * crash before the row reruns this fresh path with no prior warning and a crash after it resumes
 * through the non-emitting reuse path — at most one warning per loop and iteration either way. The
 * exact-equality check keeps later iterations silent, and the guard reads only the edge's own
 * declaration, so `review_fix` and `audit_gap` acknowledge independently with no phase-name
 * branching. Emission failures are swallowed: the transition already happened and an advisory
 * message must not be able to change it.
 */
internal fun FeatureTaskRuntimeRunLoop.warnOnThresholdCrossing(
  edge: FeatureTaskRuntimeBackwardEdge,
  edgeIteration: Int,
) {
  val threshold = edge.warnAfterIterations ?: return
  if (edgeIteration != threshold + 1) return
  runCatching { diagnostics.warning(thresholdCrossingWarning(edge.loopId, threshold, edgeIteration)) }
}

internal fun FeatureTaskRuntimeRunLoop.thresholdCrossingWarning(
  loopId: String,
  threshold: Int,
  edgeIteration: Int,
): String = "Remediation loop '$loopId' exceeded its warning threshold of $threshold: entering iteration " +
  "$edgeIteration for issue ${request.issueKey}, workflow ${request.workflowId}, subtask " +
  "${request.goalContinuation?.subtaskId ?: request.issueKey}, spec " +
  "${request.runInvariants.specReference}."

internal fun FeatureTaskRuntimeRunLoop.capExhaustedOnResume(phaseId: String): String? {
  // An operator reopen releases the per-edge cap for this phase too: the reopened record still
  // carries the loop metadata of the visit that exhausted the cap, so leaving this gate in place
  // would re-block the phase at entry and never reach the relaunch the operator asked for.
  if (operatorReopenedPhase(phaseId)) return null
  val record = state.recordFor(phaseId) ?: return null
  return capExhaustionForRecord(phaseId, record)
}

internal fun FeatureTaskRuntimeRunLoop.capExhaustionForRecord(
  phaseId: String,
  record: FeatureTaskRuntimePhaseRecord,
): String? {
  val loopId = record.loopId
  val iteration = record.edgeIteration
  if (loopId == null || iteration == null || state.isLoopLiveClaimed(loopId)) {
    return null
  }
  val edge = transitions.backwardEdges.firstOrNull { candidate ->
    candidate.loopId == loopId &&
      (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
  }
  if (edge?.destinationPhaseId == phaseId) {
    val sourceRecord = state.recordFor(edge.fromPhaseId)
    if (
      sourceRecord?.status == STATUS_BLOCKED && sourceRecord.loopId == loopId &&
      sourceRecord.edgeIteration == iteration
    ) {
      return null
    }
  }
  return edge
    ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
    ?.let { capExhaustionReason(it.loopId, iteration, it.triggeringVerdict) }
}

internal fun FeatureTaskRuntimeRunLoop.blocksWhenCapExhausted(
  edge: FeatureTaskRuntimeBackwardEdge,
  iteration: Int,
): Boolean = edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
  edge.perEdgeCap?.let { iteration >= it } == true

internal fun FeatureTaskRuntimeRunLoop.runPhaseFor(phaseId: String): String? {
  val briefingReentry = pendingReentry?.takeIf { it.phaseId == phaseId }
  if (briefingReentry != null) pendingReentry = null
  val reentry = briefingReentry ?: activeReentry?.takeIf { active ->
    transitions.backwardEdges
      .firstOrNull { it.loopId == active.loopId }
      ?.let { edge -> phaseId in spanBetween(edge.destinationPhaseId, edge.fromPhaseId) } == true
  }?.copy(phaseId = phaseId, reentryGapCriteria = emptyList())
  val outcome = runPhase(
    RunPhaseArgs(
      phaseId = phaseId,
      request = request,
      state = state,
      observability = observability,
      specSource = specSource,
      reentry = reentry,
      phaseTokenAccumulator = phaseTokenAccumulator,
    ),
  )
  outcome.regenerationTargetPhaseId?.let {
    // The launch seam quarantined an upstream record and requested regeneration. Do not record this
    // consumer as completed; signal advance() to settle it with the RECORD_REJECTED verdict so the
    // transition machinery re-enters the producer.
    recordRejectionSettlementPending = true
    return null
  }
  outcome.pausedReason?.let { return it }
  return outcome.blockedReason ?: run {
    val completedOutput = requireNotNull(outcome.completedOutput)
    state.recordCompleted(completedOutput)
    if (operatorBlockRetry?.phaseId == phaseId) operatorBlockRetryCompleted = true
    applyPlanningStop(phaseId, completedOutput)
  }
}

// Only the edge destination gets a LOOP_EDGE ledger entry carrying `verifier_reentry`, so only the
// destination may defer its start kind to that entry. Every other phase in the reopened span still
// owns its own start kind.
internal fun FeatureTaskRuntimeRunLoop.isLoopDestination(reentry: PendingReentry): Boolean =
  transitions.backwardEdges.firstOrNull { it.loopId == reentry.loopId }?.destinationPhaseId == reentry.phaseId
