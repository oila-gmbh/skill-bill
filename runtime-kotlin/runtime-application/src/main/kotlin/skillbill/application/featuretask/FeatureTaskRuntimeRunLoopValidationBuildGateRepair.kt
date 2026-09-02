package skillbill.application.featuretask

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.looseOutputEnvelope(outputText: String): Map<String, Any?>? {
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

internal fun FeatureTaskRuntimeRunLoop.gateTriageCapturedProducedOutputs(outputText: String): Map<String, Any?> {
  val produced = looseOutputEnvelope(outputText)
    ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]) }
    ?: return emptyMap()
  return buildMap {
    produced["value"]?.let { put("value", it) }
    produced["validation_repair_plan"]?.let { put("validation_repair_plan", it) }
  }
}

internal fun FeatureTaskRuntimeRunLoop.gateRepairSegmentOutput(
  run: PhaseRun,
  iteration: Int,
): FeatureTaskRuntimePhaseOutput = FeatureTaskRuntimePhaseOutput(
  phaseId = run.phaseId,
  iteration = iteration,
  payload =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
    """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
)

internal fun FeatureTaskRuntimeRunLoop.gateTriageSegmentOutput(
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

internal fun FeatureTaskRuntimeRunLoop.extractValidationGateTriagePlan(
  output: FeatureTaskRuntimePhaseOutput,
): ValidationGateTriageResult {
  val envelope = outputEnvelopeOf(output) ?: return ValidationGateTriageResult.Empty
  val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])
    ?: return ValidationGateTriageResult.Empty
  planFromProducedValue(produced["value"])?.let { return it }
  val directPlan = extractTriagePlanProse(produced["validation_repair_plan"])
  return if (!directPlan.isNullOrBlank()) {
    ValidationGateTriageResult.Captured(directPlan)
  } else {
    ValidationGateTriageResult.Empty
  }
}

internal fun FeatureTaskRuntimeRunLoop.planFromProducedValue(value: Any?): ValidationGateTriageResult? {
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

internal fun FeatureTaskRuntimeRunLoop.extractTriagePlanProse(raw: Any?): String? = when (raw) {
  is String -> raw.takeIf { it.isNotBlank() }
  null -> null
  else -> JsonSupport.mapToJsonString(
    JsonSupport.anyToStringAnyMap(raw) ?: mapOf("validation_repair_plan" to raw),
  ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
}

internal fun FeatureTaskRuntimeRunLoop.launchValidationGateRepair(
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
  val attempt = attemptOnce(
    recordRejectionAttemptArgs(
      PhaseAttemptContext(repairRun, state, iteration, observability),
      phaseTokenAccumulator = phaseTokenAccumulator,
    ),
  )
  val settled = attempt.settledOutcome
  val completed = settled?.completedOutput
  return when {
    completed != null -> ValidationGateAgentRepairResult.Completed(completed)
    settled != null -> ValidationGateAgentRepairResult.Blocked(
      settled.blockedReason
        ?: settled.pausedReason
        ?: "Validation repair attempt blocked.",
      failureDisposition = recorder.loadPhaseRecords(run.request.workflowId, run.request.dbPathOverride)
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

internal fun FeatureTaskRuntimeRunLoop.settleRuntimeOwnedValidation(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome {
  val acceptedOutput = runCatching {
    outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
  }.getOrElse { error ->
    return blockAndPersistInPhase(
      phaseBlockArgs(
        run,
        iteration,
        "Runtime-owned validation settlement did not validate: ${error.message.orEmpty()}",
        observability,
      ),
    )
  }
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
