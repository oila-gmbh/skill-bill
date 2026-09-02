package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopDriveContinued2 {
  internal fun blockCarriedForwardAudit(runLoop: FeatureTaskRuntimeRunLoop, detail: String): PhaseSettlement {
    val reason = if (detail == "missing") {
      "The runLoop.session.paused audit record carries no preserved output to settle from."
    } else {
      "The runLoop.session.paused audit could not be settled from its carried-forward output: $detail"
    }
    runLoop.collaborators.planningBranch.blockAt(runLoop, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, reason)
    return PhaseSettlement.stop()
  }

  fun nextPhaseAfter(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): String? {
    val effectiveVerdict = if (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(runLoop.request) &&
      runLoop.goalContinuationRecorder.reviewState(
        runLoop.request.workflowId,
        runLoop.request.dbPathOverride,
      )?.reviewCapReached == true
    ) {
      FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED
    } else {
      verdict
    }
    val edge = runLoop.collaborators.checkpointContinued6.matchingBackwardEdge(runLoop, phaseId, effectiveVerdict)
    edge?.let { runLoop.collaborators.backwardEdge.resumeInFlightReviewFix(runLoop, it) }?.let { return it }
    val transition = runCatching {
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = runLoop.transitions,
        currentPhaseId = phaseId,
        verdict = effectiveVerdict,
        edgeIterationCount = edge?.let {
          runLoop.collaborators.planningBranch.effectiveEdgeIterationCount(
            runLoop,
            it,
          )
        } ?: 0,
        context = FeatureTaskRuntimeTransitionContext(
          settledVerdictsByPhaseId = runLoop.state.settledVerdictsByPhaseId(),
        ),
      )
    }.getOrElse { error ->
      if (error !is FeatureTaskRuntimePhaseOrderViolationError) throw error
      runLoop.collaborators.planningBranch.blockAt(runLoop, error.phaseId, error.message.orEmpty())
      return null
    }
    val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterBuild(
      phaseId,
      FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
        phaseId,
        transition,
        runLoop.collaborators.transitions.qualityGateSelection(runLoop),
      ),
    )
    return runLoop.collaborators.transitions.transitionTarget(runLoop, phaseId, edge, effectiveVerdict, routed)
  }

  internal fun carriedForwardGoalReviewSettlement(runLoop: FeatureTaskRuntimeRunLoop): PhaseSettlement? = runCatching {
    runLoop.goalContinuationRecorder.reviewState(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  }.fold(
    onSuccess = { reviewState ->
      reviewState
        ?.takeIf { it.reviewCapReached || it.reviewSkippedByUser }
        ?.let {
          settleCarriedForwardGoalReview(
            runLoop,
            it,
            runLoop.session.activeReentry,
          )
        }
    },
    onFailure = { error ->
      runLoop.collaborators.driveContinued3.blockCarriedForwardReview(
        runLoop,
        error.message.orEmpty(),
      )
    },
  )

  internal fun settleCarriedForwardGoalReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    reviewState: GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement = runCatching {
    runLoop.goalContinuationRecorder.lastGoalReviewResult(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    )
  }.fold(
    onSuccess = { rawResult ->
      rawResult?.let { validateCarriedForwardGoalReview(runLoop, it, reviewState, reentry) }
        ?: runLoop.collaborators.driveContinued3.blockCarriedForwardReview(runLoop, "missing")
    },
    onFailure = { error ->
      runLoop.collaborators.driveContinued3.blockCarriedForwardReview(
        runLoop,
        error.message.orEmpty(),
      )
    },
  )

  internal fun validateCarriedForwardGoalReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    rawResult: String,
    reviewState: GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement = runCatching {
    val acceptedOutput = runLoop.outputValidator
      .validatePhaseOutput(rawResult, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      .requireAcceptedOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    recordCarriedForwardGoalReview(
      runLoop,
      acceptedOutput.normalizedOutput,
      acceptedOutput.repairEvidence,
      reentry,
    )
  }.fold(
    onSuccess = {
      PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
    },
    onFailure = { error ->
      runLoop.collaborators.driveContinued3.blockCarriedForwardReview(
        runLoop,
        error.message.orEmpty(),
      )
    },
  )

  internal fun recordCarriedForwardGoalReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
    reentry: PendingReentry?,
  ) {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    if (runLoop.state.isComplete(phaseId)) {
      return
    }
    val iteration = runLoop.state.nextIteration(phaseId)
    val priorRecord = runLoop.state.recordFor(phaseId)
    val persisted = runLoop.recorder.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = normalizedOutput.canonicalJson,
        normalizedOutput = normalizedOutput,
        repairEvidence = repairEvidence,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      ),
      runLoop.request.dbPathOverride,
    )
    if (!persisted) {
      error("Carried-forward goal review could not atomically persist its canonical result.")
    }
    if (reentry != null) runLoop.session.pendingReentry = null
    runLoop.state.recordCompleted(
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        repairEvidence,
      ),
    )
  }
}
