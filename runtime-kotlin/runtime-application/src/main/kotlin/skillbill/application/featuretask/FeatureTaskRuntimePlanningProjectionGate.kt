package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope

/**
 * SKILL-140 producer gate: a phase that owns a bounded planning projection must emit one that its
 * consumer can actually parse. Without this the contract was enforced only at the consumer's launch
 * seam, where the producing phase is already settled `completed` — so a malformed digest/plan/receipt
 * blocked the *next* phase with no fix loop able to reach the phase that wrote it, and the run wedged.
 * Rejecting at the producer instead re-enters that phase's own bounded fix loop.
 *
 * This is the single producer-side seam: the run-loop phase gate, the goal planning sweep, the goal
 * planning preparation write path, and the child hydrator all name this one function, so no producer
 * path can hold a second, weaker validator.
 *
 * No projection rule is restated here: acceptance is decided by the same
 * [featureTaskRuntimePlanningProjectionFromEnvelope] and the same validator port the launch seam uses,
 * which is what the parity test binds.
 */
internal fun producerProjectionGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
): String? {
  // Only a completed phase claims to have produced its projection. A blocked or failed envelope is
  // settled through the terminal path, where produced_outputs carries blocking reasons, not a claim.
  if (outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED) return null
  val expectedKind = FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId)
    ?: return null
  // A decompose plan stops the run at planning and hands the planning stopper a decomposition package,
  // which has its own contract and its own loud-fail path. It is not an executable plan and no phase
  // downstream parses it as one. Only `plan` owns that stopper backstop, so the exemption is scoped to
  // the executable-plan producer; a preplan/implement output merely shaped like a decompose package has
  // no such backstop and must still face the gate, or it settles completed and wedges its consumer.
  if (expectedKind == FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN &&
    featureTaskRuntimeIsDecompositionPackage(outputMap)
  ) {
    return null
  }
  return try {
    featureTaskRuntimePlanningProjectionFromEnvelope(
      envelope = outputMap,
      producingPhaseId = phaseId,
      expectedKind = expectedKind,
      schemaValidator = planningProjectionValidator,
    )
    null
  } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
    // The offending field lives in the error's source label; the reason is the failure text. Composing
    // both names the field (e.g. `rollout`, `deviations`) the diagnosis is actually about.
    val failure = "${error.sourceLabel}: ${error.reason}"
    "Phase '$phaseId' reported 'completed' but its produced_outputs is not a valid " +
      "'${expectedKind.wireValue}' projection, which its consumer parses verbatim. Emit produced_outputs " +
      "carrying projection_kind '${expectedKind.wireValue}' at contract_version " +
      "'${FeatureTaskRuntimePlanningProjectionContract.VERSION}' with the declared fields. " +
      "Projection validation failed: ${boundedSchemaGateDetail(failure)}"
  }
}

/**
 * The goal-side adaptation of [producerProjectionGateReason]. The goal planning preparation write path
 * and the child hydrator reach the same decision through the same function and the same validator port;
 * only the typed error differs, because on those paths a rejection is a preparation-record rejection.
 */
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
