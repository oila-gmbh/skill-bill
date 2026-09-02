package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonSupport
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopValidationGate {
  internal fun runDeclaredBuildGateCycle(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val checkpoint = runLoop.collaborators.validationGateContinued4.resolveValidationGateCheckpoint(runLoop, run)
      ?: return PhaseOutcome.blocked(
        "Build gate cycle could not resolve a repository checkpoint fingerprint.",
      )
    val iteration = state.nextIteration(run.phaseId)
    persistBuildGateRunningPhase(runLoop, run, state, iteration, observability)?.let { return it }
    val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, runLoop.phaseTokenAccumulator)
    val cycle = runLoop.buildGateCoordinator.execute(
      cycle = buildGateCycleRequest(runLoop, ValidationGateCycleRequestArgs(context, checkpoint)),
      onGateRunCount = { observability.validationGateProgress() },
    )
    return runLoop.collaborators.validationGateContinued1.settleBuildGateCycleResult(
      runLoop,
      SettleBuildGateCycleResultArgs(run, iteration, observability, checkpoint, cycle),
    )
  }

  internal fun settleRuntimeOwnedBuild(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = acceptRuntimeOwnedBuild(runLoop, run, outputText).getOrElse { error ->
      return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Runtime-owned build settlement did not validate: ${error.message.orEmpty()}",
          observability,
        ),
      )
    }
    return persistRuntimeOwnedBuildCompletion(
      runLoop,
      PersistRuntimeOwnedBuildCompletionArgs(run, iteration, outputText, observability, acceptedOutput),
    )
  }

  private fun FeatureTaskRuntimeRunLoopValidationGate.acceptRuntimeOwnedBuild(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputText: String,
  ): Result<AcceptedFeatureTaskRuntimePhaseOutput> = runCatching {
    val accepted = runLoop.outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId)
      .requireAcceptedOutput(run.phaseId)
    val buildReceipt = JsonSupport.anyToStringAnyMap(
      JsonSupport.anyToStringAnyMap(accepted.normalizedOutput.envelope["produced_outputs"])?.get("build_receipt"),
    )
    runLoop.buildReceiptValidator.validateBuildReceipt(buildReceipt ?: emptyMap(), sourceLabel = run.phaseId)
    accepted
  }

  private fun FeatureTaskRuntimeRunLoopValidationGate.persistRuntimeOwnedBuildCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PersistRuntimeOwnedBuildCompletionArgs,
  ): PhaseOutcome {
    val run = args.run
    val iteration = args.iteration
    val outputText = args.outputText
    val observability = args.observability
    val acceptedOutput = args.acceptedOutput
    val normalizedOutput = acceptedOutput.normalizedOutput
    val persisted = runLoop.recorder.recordCompletedPhase(
      runLoop.collaborators.outputPersistence.phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
          extras = PhaseStateRequestExtras(
            normalizedOutput = normalizedOutput,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
        ),
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned build settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
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

  internal fun launchValidationGateTriage(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateTriageArgs,
  ): ValidationGateTriageResult {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val findings = args.findings
    val triageRun = run.copy(validationGateFindings = findings, validationGateTriage = true)
    val attempt = runLoop.collaborators.recordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(triageRun, runLoop.state, iteration, runLoop.observability),
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val settled = attempt.settledOutcome
    val completed = settled?.completedOutput
    return when {
      completed != null -> runLoop.collaborators.validationGateContinued1.extractValidationGateTriagePlan(
        runLoop,
        completed,
      )
      settled != null -> ValidationGateTriageResult.Empty
      else -> ValidationGateTriageResult.Empty
    }
  }

  internal fun buildGateCycleRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateCycleRequestArgs,
  ): ValidationGateCycleRequest = runLoop.collaborators.validationGateContinued4.validationGateCycleRequest(
    runLoop,
    args,
  ).copy(validationDepth = ValidationDepth.DEFAULT)

  internal fun persistBuildGateRunningPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val runningPhaseState = runLoop.collaborators.outputPersistence.phaseStateRequest(
      runLoop,
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
    if (!runLoop.recorder.recordPhaseState(runningPhaseState, run.request.dbPathOverride)) {
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
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
}
