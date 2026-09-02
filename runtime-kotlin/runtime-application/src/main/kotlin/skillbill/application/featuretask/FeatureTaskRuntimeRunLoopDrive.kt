package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopDrive {
  internal fun resumedReentry(runLoop: FeatureTaskRuntimeRunLoop): PendingReentry? {
    val (loopId, reentry) = runLoop.state.latestInFlightReentry() ?: return null
    if (
      runLoop.state.spanBlockedByEntryGate(reentry.span) ||
      (
        loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in runLoop.state.completedPhaseIds()
        )
    ) {
      runLoop.state.discardStaleReentry(loopId)
      return null
    }
    runLoop.state.recordEdgeIteration(loopId, reentry.edgeIteration)
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
        reviewedCheckpointFingerprint(runLoop)
      } else {
        null
      },
    )
  }

  fun reviewedCheckpointFingerprint(runLoop: FeatureTaskRuntimeRunLoop): String? =
    runLoop.recorder.loadDeliveredProjections(runLoop.request.workflowId, runLoop.request.dbPathOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.repositoryCheckpointFingerprint

  fun phaseEntryBlockReason(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? =
    entryGateBlockReason(runLoop, phaseId)
      ?: runLoop.collaborators.backwardEdge.capExhaustedOnResume(runLoop, phaseId)
      ?: reconcileCompletedGoalReviewPass(runLoop, phaseId)

  fun entryGateBlockReason(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
    val settledVerdicts = runLoop.state.settledVerdictsByPhaseId()
    return runLoop.transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
      FeatureTaskRuntimePhaseOrderViolationError(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
      ).message
    }
  }

  fun reconcileCompletedGoalReviewPass(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? =
    if (isCompletedGoalReview(runLoop, phaseId)) {
      reconcileReservedGoalReviewPass(runLoop, phaseId)
    } else {
      null
    }

  fun isCompletedGoalReview(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(runLoop.request) &&
      runLoop.state.isComplete(phaseId)

  fun reconcileReservedGoalReviewPass(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? = runCatching {
    runLoop.goalContinuationRecorder.reviewState(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    )
  }.fold(
    onSuccess = { reviewState ->
      when {
        reviewState == null ->
          "Goal-subtask review runLoop.state is missing while reconciling a completed review pass."
        reviewState.reservedPassNumber != null ->
          reconcileReservedGoalReviewOutput(runLoop, phaseId)
        else -> null
      }
    },
    onFailure = { error ->
      "Goal-subtask review runLoop.state is malformed while reconciling a completed review pass: " +
        error.message.orEmpty()
    },
  )

  fun reconcileReservedGoalReviewOutput(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? =
    runLoop.state.outputFor(phaseId)?.payload?.let { output ->
      runCatching {
        runLoop.outputValidator.validatePhaseOutput(output, sourceLabel = phaseId)
          .requireAcceptedOutput(phaseId)
      }.fold(
        onSuccess = { accepted ->
          runLoop.collaborators.driveContinued1.completeReservedGoalReviewPass(
            runLoop,
            output,
            accepted.normalizedOutput.envelope,
          )
        },
        onFailure = { error ->
          "Completed goal-subtask review output cannot reconcile its reserved pass: " +
            error.message.orEmpty()
        },
      )
    } ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."
}
