package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

@Inject
class FeatureTaskRuntimeRunLoopBackwardEdge {
  fun resumeInFlightReviewFix(runLoop: FeatureTaskRuntimeRunLoop, edge: FeatureTaskRuntimeBackwardEdge): String? {
    if (
      edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID ||
      runLoop.state.isLoopLiveClaimed(edge.loopId) ||
      runLoop.state.isComplete(edge.destinationPhaseId)
    ) {
      return null
    }
    val destinationRecord = runLoop.state.recordFor(edge.destinationPhaseId)
      ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == runLoop.state.edgeIterationCount(edge.loopId) }
      ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    runLoop.state.reopenForReentry(edge.fromPhaseId)
    runLoop.state.recordEdgeIteration(edge.loopId, edgeIteration)
    runLoop.session.pendingReentry = PendingReentry(
      phaseId = edge.destinationPhaseId,
      loopId = edge.loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = edge.triggeringVerdict,
      expectedRepositoryCheckpoint = runLoop.collaborators.drive.reviewedCheckpointFingerprint(runLoop),
    )
    runLoop.session.activeReentry = runLoop.session.pendingReentry
    return edge.destinationPhaseId
  }

  internal fun recordBackwardEdge(runLoop: FeatureTaskRuntimeRunLoop, args: BackwardEdgeRecordArgs) {
    val edge = args.edge
    val destinationPhaseId = args.destinationPhaseId
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val verdict = args.verdict
    val reopenedSpan = runLoop.collaborators.transitions.spanBetween(runLoop, destinationPhaseId, edge.fromPhaseId)
    reopenedSpan.forEach(runLoop.state::reopenForReentry)
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      // Invalidate the quarantined producer's settled completion so its rejected record is no longer
      // selected by the handoff contract; the regenerated higher-iteration output supersedes it. In
      // memory the stale output is dropped from resolution; durably the record returns to running so a
      // resume relaunches the producer rather than re-consuming the rejected record.
      runLoop.state.invalidateProducerOutput(destinationPhaseId)
      runLoop.recorder.invalidateQuarantinedProducerRecord(
        runLoop.request.workflowId,
        destinationPhaseId,
        loopId,
        edgeIteration,
        runLoop.request.dbPathOverride,
      )
    }
    runLoop.state.recordEdgeIteration(loopId, edgeIteration)
    runLoop.session.pendingReentry = PendingReentry(
      destinationPhaseId,
      loopId,
      edgeIteration,
      verdict,
      emptyList(),
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        runLoop.collaborators.drive.reviewedCheckpointFingerprint(runLoop)
      } else {
        null
      },
    )
    runLoop.session.activeReentry = runLoop.session.pendingReentry
    runLoop.observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
    warnOnThresholdCrossing(runLoop, edge, edgeIteration)
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
  fun warnOnThresholdCrossing(
    runLoop: FeatureTaskRuntimeRunLoop,
    edge: FeatureTaskRuntimeBackwardEdge,
    edgeIteration: Int,
  ) {
    val threshold = edge.warnAfterIterations ?: return
    if (edgeIteration != threshold + 1) return
    runCatching {
      runLoop.diagnostics.warning(
        thresholdCrossingWarning(
          runLoop,
          edge.loopId,
          threshold,
          edgeIteration,
        ),
      )
    }
  }

  fun thresholdCrossingWarning(
    runLoop: FeatureTaskRuntimeRunLoop,
    loopId: String,
    threshold: Int,
    edgeIteration: Int,
  ): String = "Remediation loop '$loopId' exceeded its warning threshold of $threshold: entering iteration " +
    "$edgeIteration for issue ${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}, subtask " +
    "${runLoop.request.goalContinuation?.subtaskId ?: runLoop.request.issueKey}, spec " +
    "${runLoop.request.runInvariants.specReference}."

  fun capExhaustedOnResume(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
    // An operator reopen releases the per-edge cap for this phase too: the reopened record still
    // carries the loop metadata of the visit that exhausted the cap, so leaving this gate in place
    // would re-block the phase at entry and never reach the relaunch the operator asked for.
    if (runLoop.collaborators.phaseAttemptsContinued1.operatorReopenedPhase(runLoop, phaseId)) return null
    val record = runLoop.state.recordFor(phaseId) ?: return null
    return capExhaustionForRecord(runLoop, phaseId, record)
  }

  fun capExhaustionForRecord(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord,
  ): String? {
    val loopId = record.loopId
    val iteration = record.edgeIteration
    if (loopId == null || iteration == null || runLoop.state.isLoopLiveClaimed(loopId)) {
      return null
    }
    val edge = runLoop.transitions.backwardEdges.firstOrNull { candidate ->
      candidate.loopId == loopId &&
        (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
    }
    if (edge?.destinationPhaseId == phaseId) {
      val sourceRecord = runLoop.state.recordFor(edge.fromPhaseId)
      if (
        sourceRecord?.status == STATUS_BLOCKED && sourceRecord.loopId == loopId &&
        sourceRecord.edgeIteration == iteration
      ) {
        return null
      }
    }
    return edge
      ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
      ?.let {
        runLoop.collaborators.planningBranch.capExhaustionReason(
          runLoop,
          it.loopId,
          iteration,
          it.triggeringVerdict,
        )
      }
  }
}
