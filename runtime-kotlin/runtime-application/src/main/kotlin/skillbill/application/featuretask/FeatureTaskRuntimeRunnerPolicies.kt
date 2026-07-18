package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration

internal const val STATUS_RUNNING = "running"
internal const val STATUS_COMPLETED = "completed"
internal const val STATUS_BLOCKED = "blocked"
internal const val BRANCH_SETUP_AGENT_ID = "branch-setup"
internal const val SCHEMA_GATE_DETAIL_MAX_CHARS = 500

internal val NON_FILE_MUTATING_PHASES = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
)

internal fun serializeTokenData(accumulator: Map<String, Pair<Int, Int>>): Pair<String?, Int?> {
  if (accumulator.isEmpty()) return null to null
  val breakdown = accumulator.mapValues { (_, pair) ->
    mapOf("estimated_input_tokens" to pair.first, "estimated_output_tokens" to pair.second)
  }
  val total = accumulator.values.sumOf { (input, output) -> input + output }
  return JsonSupport.mapToJsonString(breakdown) to total
}

internal fun isFileMutating(phaseId: String): Boolean = phaseId !in NON_FILE_MUTATING_PHASES

internal fun transitionsFor(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeTransitionDeclaration =
  request.transitionsOverride ?: phasesFor(request).let { phases ->
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = phases,
      backwardEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges,
      loopOnlyPhaseIds = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
        .filter { it in phases }.toSet(),
    )
  }

internal fun phasesFor(request: FeatureTaskRuntimeRunRequest): List<String> {
  val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  return if (isGoalContinuationRun(request)) {
    phases.takeWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR }
  } else {
    phases
  }
}

internal fun mutatingReconciliationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
  val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
  val nestedReconciled = (producedOutputs?.get("reconciled_state") as? Map<*, *>)?.get("reconciled")
  val reconciled = nestedReconciled == true || producedOutputs?.get("reconciled") == true
  return if (reconciled) {
    null
  } else {
    "Mutating phase '$phaseId' reported 'completed' without a reconciliation report proving it " +
      "reconciled the working tree to target: produced_outputs must carry 'reconciled_state' (or a " +
      "'reconciled' entry) with 'reconciled' set to true. The idempotency contract is verified, not " +
      "assumed; a silent skip fails the schema gate."
  }
}

internal fun reviewVerificationSignalGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
  val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
  val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
  val findingsKey = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
  val hasFindingsArray = producedOutputs?.containsKey(findingsKey) == true && producedOutputs[findingsKey] is List<*>
  return if (hasVerdict || hasFindingsArray) {
    null
  } else {
    "Review phase reported 'completed' without a verification signal: the output must carry either a " +
      "top-level 'verdict' or a 'produced_outputs.findings' array (an explicit empty array affirms no " +
      "blocking findings). A review that emits neither cannot advance past a possible Blocker; " +
      "the schema gate fails rather than silently advancing to audit."
  }
}

internal fun auditVerificationSignalGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
  FeatureTaskRuntimeOutputVerification.auditGapPayloadError(outputMap)?.let { return it }
  val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
  val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
  val hasCriteriaArray = listOf(
    FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA,
    FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_ALIAS,
  ).any { key -> producedOutputs?.containsKey(key) == true && producedOutputs[key] is List<*> }
  return if (hasVerdict || hasCriteriaArray) {
    null
  } else {
    "Audit phase reported 'completed' without a verification signal: the output must carry either a " +
      "top-level 'verdict' or a 'produced_outputs.unmet_criteria' array (an explicit empty array affirms " +
      "every acceptance criterion is met). An audit that emits neither cannot advance past a possibly-unmet " +
      "criterion; the schema gate fails rather than silently advancing to validate."
  }
}

internal fun withSchemaGateDetail(policyReason: String, validationReason: String): String {
  val bounded = if (validationReason.length <= SCHEMA_GATE_DETAIL_MAX_CHARS) {
    validationReason
  } else {
    validationReason.take(SCHEMA_GATE_DETAIL_MAX_CHARS) + "… [truncated]"
  }
  return "$policyReason Last schema-gate failure: $bounded"
}
