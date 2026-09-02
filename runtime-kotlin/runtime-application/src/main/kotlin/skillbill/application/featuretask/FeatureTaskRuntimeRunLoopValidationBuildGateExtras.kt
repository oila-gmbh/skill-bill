package skillbill.application.featuretask

import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal fun FeatureTaskRuntimeRunLoop.buildGateCycleRequest(
  args: ValidationGateCycleRequestArgs,
): ValidationGateCycleRequest = validationGateCycleRequest(args).copy(validationDepth = ValidationDepth.DEFAULT)

internal fun FeatureTaskRuntimeRunLoop.persistBuildGateRunningPhase(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome? {
  val runningPhaseState = phaseStateRequest(
    PhaseStateRequestArgs(
      write = PhaseStateWriteArgs(
        run = run,
        iteration = iteration,
        status = STATUS_RUNNING,
        finished = false,
        outputArtifact = null,
      ),
    ),
  )
  state.reserveReviewPass(runningPhaseState.reviewPassNumber)
  if (!recorder.recordPhaseState(runningPhaseState, run.request.dbPathOverride)) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = "Build gate cycle could not persist running build phase before gate execution.",
        observability = observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      ),
    )
  }
  observability.started(
    run.phaseId,
    run.resolvedAgent.resolvedAgentId,
    iteration,
    run.modelDirective,
    FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
  )
  return null
}

internal fun FeatureTaskRuntimeRunLoop.settleBuildGateCycleResult(
  run: PhaseRun,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
  checkpoint: String,
  cycle: ValidationGateCycleResult,
): PhaseOutcome = when (cycle) {
  ValidationGateCycleResult.AbsentFallback ->
    settleRuntimeOwnedBuild(
      run,
      iteration,
      FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
        repositoryCheckpoint = checkpoint,
        measurements = emptyList(),
        checks = emptyList(),
      ).payload,
      observability,
    )
  is ValidationGateCycleResult.Terminal ->
    when (val terminal = cycle.outcome) {
      is ValidationGateCycleTerminalOutcome.Completed ->
        settleRuntimeOwnedBuild(run, iteration, terminal.output.payload, observability)
      is ValidationGateCycleTerminalOutcome.Blocked ->
        blockInPhase(
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = terminal.reason,
            observability = observability,
            failureDisposition = terminal.failureDisposition
              ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          ),
        )
    }
}
