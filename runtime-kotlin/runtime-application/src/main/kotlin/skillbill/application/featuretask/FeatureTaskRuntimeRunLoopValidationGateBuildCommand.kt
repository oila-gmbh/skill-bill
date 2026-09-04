package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopValidationGateBuildCommand {
  internal fun planFromProducedValue(value: Any?): ValidationGateTriageResult? {
    val valueText = value as? String ?: return null
    if (valueText.isBlank()) return null
    val inner = JsonSupport.parseObjectOrNull(valueText)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
    val planFromValue = inner?.let { extractTriagePlanProse(it["validation_repair_plan"]) }
    if (!planFromValue.isNullOrBlank()) {
      return ValidationGateTriageResult.Captured(planFromValue)
    }
    return if (inner == null) ValidationGateTriageResult.Captured(valueText) else null
  }

  internal fun extractTriagePlanProse(raw: Any?): String? = when (raw) {
    is String -> raw.takeIf { it.isNotBlank() }
    null -> null
    else -> JsonSupport.mapToJsonString(
      JsonSupport.anyToStringAnyMap(raw) ?: mapOf("validation_repair_plan" to raw),
    ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
  }

  internal fun launchValidationGateRepair(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateRepairArgs,
  ): ValidationGateAgentRepairResult {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val findings = args.findings
    val repairTurn = args.repairTurn
    val triagePlan = args.triagePlan
    val repairRun = run.copy(
      validationGateFindings = findings,
      validationGateRepairTurn = repairTurn,
      validationGateTriagePlan = triagePlan,
      validationGateRepair = true,
    )
    val attempt = runLoop.collaborators.recordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(repairRun, runLoop.state, iteration, runLoop.observability),
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val settled = attempt.settledOutcome
    val completed = settled?.completedOutput
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      settled != null -> ValidationGateAgentRepairResult.Blocked(
        settled.blockedReason
          ?: settled.pausedReason
          ?: "Validation repair attempt runLoop.session.blocked.",
        failureDisposition = runLoop.recorder.loadPhaseRecords(run.request.workflowId, run.request.dbPathOverride)
          ?.get(run.phaseId)
          ?.failureDisposition,
      )
      else -> ValidationGateAgentRepairResult.Completed(
        FeatureTaskRuntimePhaseOutput(
          phaseId = run.phaseId,
          iteration = iteration,
          payload =
          """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
            """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
        ),
      )
    }
  }

  internal fun settleRuntimeOwnedValidation(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      runLoop.outputValidator.validatePhaseOutput(
        outputText,
        sourceLabel = run.phaseId,
      ).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Runtime-owned validation settlement did not validate: ${error.message.orEmpty()}",
          observability,
        ),
      )
    }
    return finishRuntimeOwnedValidation(
      RuntimeOwnedValidationFinishArgs(runLoop, run, iteration, outputText, acceptedOutput, observability),
    )
  }

  private fun finishRuntimeOwnedValidation(args: RuntimeOwnedValidationFinishArgs): PhaseOutcome {
    val runLoop = args.runLoop
    val run = args.run
    val iteration = args.iteration
    val outputText = args.outputText
    val acceptedOutput = args.acceptedOutput
    val observability = args.observability
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
          reason = "Runtime-owned validation settlement could not be persisted.",
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

  internal fun validationChangedPaths(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): List<String> =
    runLoop.collaborators.outputVerificationContinued1.resolveRepositoryCheckpoint(
      runLoop,
      run,
    )?.workingTreeOwnedPaths.orEmpty().distinct().sorted()

  internal fun packCollectAllCommand(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
      return null
    }
    return when (
      val resolution = runLoop.phaseGates.validationGateResolver.resolve(
        validationChangedPaths(
          runLoop,
          run,
        ),
      )
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.collectAllFullGateCommand.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }
}

private data class RuntimeOwnedValidationFinishArgs(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val run: PhaseRun,
  val iteration: Int,
  val outputText: String,
  val acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  val observability: FeatureTaskRuntimeRunObservability,
)
