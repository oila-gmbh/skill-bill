package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

@Inject
class FeatureTaskRuntimeRunLoopValidationGateCollectCommand {
  internal fun settleBuildGateCycleResult(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleBuildGateCycleResultArgs,
  ): PhaseOutcome {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val checkpoint = args.checkpoint
    val cycle = args.cycle
    return when (cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        runLoop.collaborators.validationGate.settleRuntimeOwnedBuild(
          runLoop,
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
            runLoop.collaborators.validationGate.settleRuntimeOwnedBuild(
              runLoop,
              run,
              iteration,
              terminal.output.payload,
              observability,
            )
          is ValidationGateCycleTerminalOutcome.Blocked ->
            runLoop.collaborators.phaseAttempts.blockInPhase(
              runLoop,
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
  }

  fun looseOutputEnvelope(outputText: String): Map<String, Any?>? {
    val trimmed = outputText.trim()
    JsonSupport.parseObjectOrNull(trimmed)?.let {
      return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
    }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start !in 0..<end) return null
    return JsonSupport.parseObjectOrNull(trimmed.substring(start, end + 1))
      ?.let { JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it)) }
  }

  fun gateTriageCapturedProducedOutputs(outputText: String): Map<String, Any?> {
    val produced = looseOutputEnvelope(outputText)
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]) }
      ?: return emptyMap()
    return buildMap {
      produced["value"]?.let { put("value", it) }
      produced["validation_repair_plan"]?.let { put("validation_repair_plan", it) }
    }
  }

  internal fun gateRepairSegmentOutput(run: PhaseRun, iteration: Int): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
        """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
    )

  internal fun gateTriageSegmentOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): FeatureTaskRuntimePhaseOutput {
    val captured = gateTriageCapturedProducedOutputs(outputText)
    val payload = mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to run.phaseId,
      "status" to "completed",
      "summary" to "Gate triage segment.",
      "produced_outputs" to captured,
    )
    return FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload = JsonSupport.mapToJsonString(payload),
    )
  }

  fun extractValidationGateTriagePlan(
    runLoop: FeatureTaskRuntimeRunLoop,
    output: FeatureTaskRuntimePhaseOutput,
  ): ValidationGateTriageResult {
    val envelope = runLoop.collaborators.launchContinued3.outputEnvelopeOf(output)
      ?: return ValidationGateTriageResult.Empty
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])
      ?: return ValidationGateTriageResult.Empty
    runLoop.collaborators.validationGateContinued2.planFromProducedValue(produced["value"])?.let { return it }
    val directPlan = runLoop.collaborators.validationGateContinued2
      .extractTriagePlanProse(produced["validation_repair_plan"])
    return if (!directPlan.isNullOrBlank()) {
      ValidationGateTriageResult.Captured(directPlan)
    } else {
      ValidationGateTriageResult.Empty
    }
  }
}
