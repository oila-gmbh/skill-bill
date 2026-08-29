package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext

internal fun retryCorrectionDirective(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  priorSchemaFailure: String?,
  correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
): String {
  if (priorSchemaFailure.isNullOrBlank()) {
    return ""
  }
  val base = """
    ## Previous attempt was REJECTED by the schema gate — salvage the capture
    Programmatic extraction and shape repair could not accept the previous output. Reason:
    $priorSchemaFailure
    This is the last salvage attempt. Rewrite the untrusted prior output into exactly one JSON object
    matching the expected shape below. Keep facts already in that capture; do not redo the phase work
    and do not emit a second envelope. The runtime will extract and validate the result the same way;
    if it still fails, the run blocks.

    Expected shape:
  """.trimIndent() + "\n" + retrySkeleton(briefing)
  val structuralRepairNote = correctiveRepairContext?.structuralRepairEvidence?.let { evidence ->
    "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only; " +
      "original_digest=${evidence.originalDigest} repaired_digest=${evidence.repairedDigest} " +
      "source=${evidence.sourceLocation.sourceLabel}:" +
      "${evidence.sourceLocation.line}:${evidence.sourceLocation.column}). " +
      "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
  } ?: if (correctiveRepairContext?.acceptedAfterStructuralRepair == true) {
    "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only). " +
      "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
  } else {
    ""
  }
  val repairProjection = correctiveRepairContext?.let { context ->
    "\n\n" + context.promptProjection().renderAuthorizedRepairSection()
  }.orEmpty()
  return base + structuralRepairNote + repairProjection +
    unparseableRootCorrection(priorSchemaFailure) +
    FeatureTaskRuntimeSchemaFailureCorrections.lengthViolation(priorSchemaFailure) +
    FeatureTaskRuntimeSchemaFailureCorrections.closedEnumeration(priorSchemaFailure) +
    FeatureTaskRuntimeSchemaFailureCorrections.unreconciledReceipt(priorSchemaFailure)
}

private fun unparseableRootCorrection(priorSchemaFailure: String): String {
  val rootNotParseable = priorSchemaFailure.contains("<root> must be an object") ||
    priorSchemaFailure.contains("Phase output is malformed")
  if (!rootNotParseable) {
    return ""
  }
  return "\nThe runtime could NOT parse a single JSON object out of your previous output — you likely " +
    "answered\nwith prose, a Markdown table, or a JSON array. None of those can advance the gate. Salvage " +
    "that capture into the expected shape above."
}

private fun retrySkeleton(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String = buildList {
  val phaseId = briefing.phaseId
  add("```json")
  add("{")
  add("  \"contract_version\": \"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\",")
  add("  \"phase_id\": \"$phaseId\",")
  add("  \"status\": \"completed\",")
  verdictSkeletonLine(phaseId)?.let(::add)
  add("  \"summary\": \"<one sentence describing what this phase did>\",")
  add("  \"produced_outputs\": { ${producedOutputsSkeletonEntry(briefing)} }")
  add("}")
  add("```")
}.joinToString(separator = "\n")

private fun verdictSkeletonLine(phaseId: String): String? {
  val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
  return when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> "  \"$verdict\": \"satisfied\","
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> "  \"$verdict\": \"approved\","
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> "  \"$verdict\": \"findings_verified\","
    else -> null
  }
}

private fun producedOutputsSkeletonEntry(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
  when (briefing.phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditProducedOutputsSkeleton()
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
      "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS}\": [], " +
        "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": \"<the Review run ID this pass " +
        "reported>\""
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
      "\"${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}\": [ " +
        "{ \"finding_id\": \"F-001\", \"disposition\": \"verified\" } ]"
    else -> "\"result\": \"<concrete output for downstream phases>\""
  }

private fun auditProducedOutputsSkeleton(): String {
  val innerGaps = "\"gaps\":[],\"non_blocking_findings\":[]"
  return "\"value\": \"{$innerGaps}\""
}
