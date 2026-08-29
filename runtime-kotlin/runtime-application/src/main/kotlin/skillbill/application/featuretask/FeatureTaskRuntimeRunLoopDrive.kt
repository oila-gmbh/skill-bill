package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.resumedReentry(): PendingReentry? {
  val (loopId, reentry) = state.latestInFlightReentry() ?: return null
  if (
    state.spanBlockedByEntryGate(reentry.span) ||
    (
      loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in state.completedPhaseIds()
      )
  ) {
    state.discardStaleReentry(loopId)
    return null
  }
  state.recordEdgeIteration(loopId, reentry.edgeIteration)
  val resumePhaseId = reentry.resumePhaseId
  return PendingReentry(
    phaseId = resumePhaseId,
    loopId = loopId,
    edgeIteration = reentry.edgeIteration,
    drivingVerdict = reentry.drivingVerdict,
    reentryGapCriteria = emptyList(),
    expectedRepositoryCheckpoint = if (
      loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    ) {
      reviewedCheckpointFingerprint()
    } else {
      null
    },
  )
}

internal fun FeatureTaskRuntimeRunLoop.reviewedCheckpointFingerprint(): String? =
  recorder.loadDeliveredProjections(request.workflowId, request.dbPathOverride)
    ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    ?.repositoryCheckpointFingerprint

internal fun FeatureTaskRuntimeRunLoop.phaseEntryBlockReason(phaseId: String): String? = entryGateBlockReason(phaseId)
  ?: capExhaustedOnResume(phaseId)
  ?: reconcileCompletedGoalReviewPass(phaseId)

// The phase-entry seam of the declared ordering gate. drive() can enter a phase directly from a
// resumed pending re-entry without ever consulting the transition function, so guarding only the
// transition would leave a resume hole through which a stale durable record re-enters a gated
// phase. Both seams evaluate the same declaration-owned predicate.
//
// The violation degrades to a durable, resumable Blocked report rather than an escaping throw:
// an uncaught contract exception here would leave the workflow row running with no blocked reason
// and skip goal-continuation outcome persistence, so the parent goal could neither resume nor
// report. Every other governed gate in this runtime blocks the same way.
internal fun FeatureTaskRuntimeRunLoop.entryGateBlockReason(phaseId: String): String? {
  val settledVerdicts = state.settledVerdictsByPhaseId()
  return transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
    FeatureTaskRuntimePhaseOrderViolationError(
      phaseId = gate.phaseId,
      requiredPhaseId = gate.requiredPhaseId,
      requiredVerdict = gate.requiredVerdict.wireValue,
      observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
    ).message
  }
}

internal fun FeatureTaskRuntimeRunLoop.reconcileCompletedGoalReviewPass(phaseId: String): String? =
  if (isCompletedGoalReview(phaseId)) reconcileReservedGoalReviewPass(phaseId) else null

internal fun FeatureTaskRuntimeRunLoop.isCompletedGoalReview(phaseId: String): Boolean =
  phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
    isGoalContinuationRun(request) &&
    state.isComplete(phaseId)

internal fun FeatureTaskRuntimeRunLoop.reconcileReservedGoalReviewPass(phaseId: String): String? = runCatching {
  goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
}.fold(
  onSuccess = { reviewState ->
    when {
      reviewState == null -> "Goal-subtask review state is missing while reconciling a completed review pass."
      reviewState.reservedPassNumber != null -> reconcileReservedGoalReviewOutput(phaseId)
      else -> null
    }
  },
  onFailure = { error ->
    "Goal-subtask review state is malformed while reconciling a completed review pass: ${error.message.orEmpty()}"
  },
)

internal fun FeatureTaskRuntimeRunLoop.reconcileReservedGoalReviewOutput(phaseId: String): String? = state.outputFor(
  phaseId,
)?.payload
  ?.let { output ->
    runCatching {
      outputValidator.validatePhaseOutput(output, sourceLabel = phaseId).requireAcceptedOutput(phaseId)
    }.fold(
      onSuccess = { accepted -> completeReservedGoalReviewPass(output, accepted.normalizedOutput.envelope) },
      onFailure = { error ->
        "Completed goal-subtask review output cannot reconcile its reserved pass: ${error.message.orEmpty()}"
      },
    )
  }
  ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."

internal fun FeatureTaskRuntimeRunLoop.completeReservedGoalReviewPass(
  output: String,
  outputMap: Map<String, Any?>,
): String? {
  val recordedVerdicts = recorder.recordedFindingVerdicts(outputMap, request.dbPathOverride)
  val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
  val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
  return if (
    goalContinuationRecorder.completeGoalReviewPass(
      request = GoalReviewPassCompletionRequest(
        workflowId = request.workflowId,
        verdict = outcome.verdict,
        unresolvedFindingCount = outcome.unresolvedFindingCount,
        findings = findings,
        rawReviewResult = output,
        normalizedOutput = outputMap,
        blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
          outputMap,
          priorBlockerFindingIds(),
        ),
        commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
      ),
      dbOverride = request.dbPathOverride,
    ) == null
  ) {
    "Completed goal-subtask review could not persist its reserved pass."
  } else {
    null
  }
}
