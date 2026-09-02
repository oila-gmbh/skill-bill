package skillbill.application.planningprojection

import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract

private const val PHASE_OUTPUT_STATUS_COMPLETED = "completed"
private const val SCHEMA_GATE_DETAIL_MAX_CHARS = 500

private fun boundedSchemaGateDetail(validationReason: String): String =
  if (validationReason.length <= SCHEMA_GATE_DETAIL_MAX_CHARS) {
    validationReason
  } else {
    validationReason.take(SCHEMA_GATE_DETAIL_MAX_CHARS) + "… [truncated]"
  }

fun producerProjectionGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
): String? {
  if (outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED) return null
  val expectedKind = FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId)
    ?: return null
  return unresolvedProducerProjectionKindReason(phaseId, expectedKind, planningProjectionValidator)
}

fun requireValidPlanningProjection(
  envelope: Map<String, Any?>,
  phaseId: String,
  sourceLabel: String,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  fieldPath: String = "${phaseId}_payload",
) {
  producerProjectionGateReason(phaseId, envelope, planningProjectionValidator)?.let { reason ->
    throw InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = sourceLabel,
      fieldPath = fieldPath,
      reason = boundedSchemaGateDetail(reason),
    )
  }
}

private fun unresolvedProducerProjectionKindReason(
  phaseId: String,
  expectedKind: String,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
): String {
  val validatorLabel = planningProjectionValidator::class.qualifiedName
    ?: planningProjectionValidator::class.java.name
  return "Phase '$phaseId' reported 'completed' but producedProjectionKindFor names '$expectedKind' " +
    "while no producer-side planning projection parser is wired for that kind " +
    "(validator=$validatorLabel)."
}
