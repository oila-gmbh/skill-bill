package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

fun FeatureTaskRuntimeRunState.auditGapPlanningContextError(): String? = listOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
).firstNotNullOfOrNull(::planningContextError)

fun FeatureTaskRuntimeRunState.planningContextError(phaseId: String): String? {
  val record = initialRecords[phaseId]
  val output = outputFor(phaseId)
  if (output == null || record?.status?.let { it != STATUS_COMPLETED } == true) {
    return "Audit-gap remediation requires a valid completed original '$phaseId' output."
  }
  val validatedOutput = output.normalizedOutput?.envelope
  return when {
    validatedOutput == null ->
      "Audit-gap remediation requires a valid completed original '$phaseId' output; " +
        "its durable record carries no normalized output."
    validatedOutput["phase_id"] != phaseId ->
      "Audit-gap remediation requires a valid completed original '$phaseId' output; " +
        "the persisted record declares phase_id '${validatedOutput["phase_id"]}'."
    record?.loopId != null || record?.edgeIteration != null ->
      "Audit-gap remediation cannot prove original planning-context identity because '$phaseId' " +
        "carries legacy backward-edge metadata. Migrate or restart this experimental durable workflow; " +
        "the runtime will not regenerate or silently reuse overwritten planning context."
    else -> null
  }
}
