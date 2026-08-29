package skillbill.application.featuretask

import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.runDeclaredBuildGateCycle(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
): PhaseOutcome {
  val checkpoint = resolveValidationGateCheckpoint(run)
    ?: return PhaseOutcome.blocked(
      "Build gate cycle could not resolve a repository checkpoint fingerprint.",
    )
  val iteration = state.nextIteration(run.phaseId)
  persistBuildGateRunningPhase(run, state, iteration, observability)?.let { return it }
  val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, phaseTokenAccumulator)
  val cycle = buildGateCoordinator.execute(
    cycle = buildGateCycleRequest(ValidationGateCycleRequestArgs(context, checkpoint)),
    onGateRunCount = { observability.validationGateProgress() },
  )
  return settleBuildGateCycleResult(run, iteration, observability, checkpoint, cycle)
}

internal fun FeatureTaskRuntimeRunLoop.settleRuntimeOwnedBuild(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome {
  val acceptedOutput = acceptRuntimeOwnedBuild(run, outputText).getOrElse { error ->
    return blockAndPersistInPhase(
      phaseBlockArgs(
        run,
        iteration,
        "Runtime-owned build settlement did not validate: ${error.message.orEmpty()}",
        observability,
      ),
    )
  }
  return persistRuntimeOwnedBuildCompletion(run, iteration, outputText, observability, acceptedOutput)
}

private fun FeatureTaskRuntimeRunLoop.acceptRuntimeOwnedBuild(
  run: PhaseRun,
  outputText: String,
): Result<AcceptedFeatureTaskRuntimePhaseOutput> = runCatching {
  val accepted = outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId)
    .requireAcceptedOutput(run.phaseId)
  val buildReceipt = JsonSupport.anyToStringAnyMap(
    JsonSupport.anyToStringAnyMap(accepted.normalizedOutput.envelope["produced_outputs"])?.get("build_receipt"),
  )
  buildReceiptValidator.validateBuildReceipt(buildReceipt ?: emptyMap(), sourceLabel = run.phaseId)
  accepted
}

private fun FeatureTaskRuntimeRunLoop.persistRuntimeOwnedBuildCompletion(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  observability: FeatureTaskRuntimeRunObservability,
  acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
): PhaseOutcome {
  val normalizedOutput = acceptedOutput.normalizedOutput
  val persisted = recorder.recordCompletedPhase(
    phaseStateRequest(
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
    return blockInPhase(
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

internal fun FeatureTaskRuntimeRunLoop.launchValidationGateTriage(
  args: ValidationGateTriageArgs,
): ValidationGateTriageResult {
  val run = args.context.attempt.run
  val state = args.context.attempt.state
  val iteration = args.context.attempt.iteration
  val observability = args.context.attempt.observability
  val phaseTokenAccumulator = args.context.phaseTokenAccumulator
  val findings = args.findings
  val triageRun = run.copy(validationGateFindings = findings, validationGateTriage = true)
  val attempt = attemptOnce(
    recordRejectionAttemptArgs(
      PhaseAttemptContext(triageRun, state, iteration, observability),
      phaseTokenAccumulator = phaseTokenAccumulator,
    ),
  )
  val settled = attempt.settledOutcome
  val completed = settled?.completedOutput
  return when {
    completed != null -> extractValidationGateTriagePlan(completed)
    settled != null -> ValidationGateTriageResult.Empty
    else -> ValidationGateTriageResult.Empty
  }
}
