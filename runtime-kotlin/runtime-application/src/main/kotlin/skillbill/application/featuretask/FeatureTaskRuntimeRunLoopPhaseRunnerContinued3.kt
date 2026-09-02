package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopPhaseRunnerContinued3 {
  internal fun settleCarriedForwardGoalReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = loadCarriedForwardGoalReviewOutput(runLoop, run).getOrElse { error ->
      return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersist(
        runLoop,
        carriedForwardMissingReviewBlock(run, state, observability, error),
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = runLoop.collaborators.outputPersistence.phaseStateRequest(
      runLoop,
      PhaseStateRequestArgs(
        write = PhaseStateWriteArgs(
          run = run,
          iteration = iteration,
          status = STATUS_COMPLETED,
          finished = true,
          outputArtifact = normalizedOutput.canonicalJson,
        ),
        extras = PhaseStateRequestExtras(
          normalizedOutput = normalizedOutput,
          repairEvidence = acceptedOutput.repairEvidence,
        ),
      ),
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    carriedForwardReviewPersistenceFailure(runLoop, phaseState, run)?.let { failure ->
      return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersist(
        runLoop,
        BlockAndPersistArgs(
          run = run,
          attemptCount = iteration,
          reason = failure,
          observability = observability,
          loopId = null,
          edgeIteration = null,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          payload = BlockAndPersistPayload(
            normalizedOutput = normalizedOutput,
            outputArtifact = normalizedOutput.canonicalJson,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
        ),
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }

  internal fun carriedForwardReviewPersistenceFailure(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    run: PhaseRun,
  ): String? {
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching {
      runLoop.recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    }.fold(
      onSuccess = { persisted -> if (persisted) null else prefix },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  internal fun loadCarriedForwardGoalReviewOutput(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun) = runCatching {
    val output = runLoop.goalContinuationRecorder.lastGoalReviewResult(
      run.request.workflowId,
      run.request.dbPathOverride,
    )
      ?: throw MissingCarriedForwardGoalReviewResultException()
    runLoop.outputValidator.validatePhaseOutput(output, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
  }

  internal fun carriedForwardMissingReviewBlock(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    error: Throwable,
  ): BlockAndPersistArgs {
    val detail = if (error is MissingCarriedForwardGoalReviewResultException) {
      "missing."
    } else {
      "malformed: ${error.message.orEmpty()}"
    }
    return BlockAndPersistArgs(
      run = run,
      attemptCount = state.nextIteration(run.phaseId),
      reason = "Goal-subtask review pass budget is exhausted but its durable raw " +
        "review result is $detail",
      observability = observability,
      loopId = null,
      edgeIteration = null,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      payload = BlockAndPersistPayload(),
    )
  }
}
