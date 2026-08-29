package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.abandonAuditGapSubtask(pause: FeatureTaskRuntimeAuditGapPause) {
  recorder.persistAuditGapPause(
    request.workflowId,
    pause.copy(grantConsumed = true, operatorDecision = null),
    request.dbPathOverride,
  )
  blockAt(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    "The operator chose abandon_subtask while the subtask was paused on the audit gap: ${pause.reason}",
  )
  goalContinuationRecorder.recordGoalContinuationState(
    GoalContinuationStateRecordRequest(
      workflowId = request.workflowId,
      workflowStatus = STATUS_ABANDONED,
    ),
    dbOverride = request.dbPathOverride,
  )
}

/**
 * Resume seam for a run parked on an audit-gap pause with an unconsumed retry_fix: settles the
 * paused audit phase from its preserved output (mirroring [carriedForwardGoalReviewSettlement]) so
 * the transition seam can take the audit_gap edge. Returns null when no retry is pending or the
 * grant is stale after a satisfied audit already advanced, letting the normal phase path run.
 */
internal fun FeatureTaskRuntimeRunLoop.settleCarriedForwardAuditGapAudit(): PhaseSettlement? = runCatching {
  recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)
}.fold(
  onSuccess = { pause ->
    if (pause == null || pause.operatorDecision != AUDIT_GAP_PAUSE_DECISION_RETRY_FIX || pause.grantConsumed) {
      null
    } else {
      settleCarriedForwardAudit(pause)
    }
  },
  onFailure = { error -> blockCarriedForwardAudit(error.message.orEmpty()) },
)

internal fun FeatureTaskRuntimeRunLoop.settleCarriedForwardAudit(
  pause: FeatureTaskRuntimeAuditGapPause,
): PhaseSettlement {
  val auditPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
  if (
    state.isComplete(auditPhaseId) &&
    state.verdictFor(auditPhaseId) == FeatureTaskRuntimeVerdict.SATISFIED
  ) {
    consumeAuditGapRetryGrant(pause)
    return PhaseSettlement.completed(auditPhaseId, FeatureTaskRuntimeVerdict.SATISFIED)
  }
  val outputArtifact = state.recordFor(auditPhaseId)?.outputArtifact
    ?: return blockCarriedForwardAudit("missing")
  return runCatching {
    val acceptedOutput = outputValidator
      .validatePhaseOutput(outputArtifact, auditPhaseId)
      .requireAcceptedOutput(auditPhaseId)
    val derivedVerdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      auditPhaseId,
      acceptedOutput.normalizedOutput.envelope,
    )
    if (!state.isComplete(auditPhaseId)) {
      recordCarriedForwardAudit(acceptedOutput.normalizedOutput, acceptedOutput.repairEvidence)
    }
    consumeAuditGapRetryGrant(pause)
    PhaseSettlement.completed(auditPhaseId, derivedVerdict)
  }.fold(
    onSuccess = { it },
    onFailure = { error -> blockCarriedForwardAudit(error.message.orEmpty()) },
  )
}

internal fun FeatureTaskRuntimeRunLoop.consumeAuditGapRetryGrant(pause: FeatureTaskRuntimeAuditGapPause) {
  recorder.persistAuditGapPause(
    request.workflowId,
    pause.copy(grantConsumed = true, operatorDecision = null),
    request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.recordCarriedForwardAudit(
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
) {
  val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
  if (state.isComplete(phaseId)) {
    return
  }
  val iteration = state.nextIteration(phaseId)
  val priorRecord = state.recordFor(phaseId)
  val persisted = recorder.recordCompletedPhase(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = request.workflowId,
      phaseId = phaseId,
      status = STATUS_COMPLETED,
      attemptCount = iteration,
      resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
      finished = true,
      outputArtifact = normalizedOutput.canonicalJson,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
      edgeIteration = priorRecord?.edgeIteration,
    ),
    request.dbPathOverride,
  )
  if (!persisted) {
    error("Carried-forward audit could not atomically persist its canonical result.")
  }
  state.recordCompleted(
    FeatureTaskRuntimePhaseOutput(
      phaseId,
      iteration,
      normalizedOutput.canonicalJson,
      normalizedOutput,
      repairEvidence,
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.blockCarriedForwardAudit(detail: String): PhaseSettlement {
  val reason = if (detail == "missing") {
    "The paused audit record carries no preserved output to settle from."
  } else {
    "The paused audit could not be settled from its carried-forward output: $detail"
  }
  blockAt(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, reason)
  return PhaseSettlement.stop()
}

internal fun FeatureTaskRuntimeRunLoop.nextPhaseAfter(phaseId: String, verdict: FeatureTaskRuntimeVerdict): String? {
  val effectiveVerdict = if (
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
    isGoalContinuationRun(request) &&
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)?.reviewCapReached == true
  ) {
    FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED
  } else {
    verdict
  }
  val edge = matchingBackwardEdge(phaseId, effectiveVerdict)
  edge?.let(::resumeInFlightReviewFix)?.let { return it }
  val transition = runCatching {
    FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = transitions,
      currentPhaseId = phaseId,
      verdict = effectiveVerdict,
      edgeIterationCount = edge?.let { effectiveEdgeIterationCount(it) } ?: 0,
      context = FeatureTaskRuntimeTransitionContext(
        settledVerdictsByPhaseId = state.settledVerdictsByPhaseId(),
      ),
    )
  }.getOrElse { error ->
    if (error !is FeatureTaskRuntimePhaseOrderViolationError) throw error
    blockAt(error.phaseId, error.message.orEmpty())
    return null
  }
  val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterBuild(
    phaseId,
    FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
      phaseId,
      transition,
      qualityGateSelection(),
    ),
  )
  return transitionTarget(phaseId, edge, effectiveVerdict, routed)
}
