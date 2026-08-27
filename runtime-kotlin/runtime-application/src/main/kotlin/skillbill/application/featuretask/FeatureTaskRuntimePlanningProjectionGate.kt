package skillbill.application.featuretask

import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract

internal fun producerProjectionGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
): String? {
  if (outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED) return null
  FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId) ?: return null
  return null
}

internal fun requireValidPlanningProjection(
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
