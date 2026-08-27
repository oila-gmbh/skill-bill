package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope

internal fun producerProjectionGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
): String? {
  if (outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED) return null
  val expectedKind = FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId)
    ?: return null
  return try {
    featureTaskRuntimePlanningProjectionFromEnvelope(
      envelope = outputMap,
      producingPhaseId = phaseId,
      expectedKind = expectedKind,
      schemaValidator = planningProjectionValidator,
    )
    null
  } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
    val failure = "${error.sourceLabel}: ${error.reason}"
    "Phase '$phaseId' reported 'completed' but its produced_outputs is not a valid " +
      "'${expectedKind.wireValue}' projection, which its consumer parses verbatim. Emit produced_outputs " +
      "carrying projection_kind '${expectedKind.wireValue}' at contract_version " +
      "'${FeatureTaskRuntimePlanningProjectionContract.VERSION}' with the declared fields. " +
      "Projection validation failed: ${boundedSchemaGateDetail(failure)}"
  }
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
