package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.review.CodeReviewExecutionMode

/**
 * Durable run-scoped invariants resolved at run creation. Resume reads this artifact instead of
 * re-resolving from the spec, so feature size and other governing inputs cannot drift mid-run.
 */
const val FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY: String = "feature_task_runtime_run_invariants"

@OpenBoundaryMap("Feature-task-runtime run-invariants artifact map at the durable workflow-artifact seam")
fun FeatureTaskRuntimeRunInvariants.toArtifactMap(): Map<String, Any?> = linkedMapOf(
  "spec_reference" to specReference,
  "feature_size" to featureSize.name,
  "acceptance_criteria" to acceptanceCriteria,
  "mandates_and_overrides" to mandatesAndOverrides,
  "code_review_mode" to codeReviewMode.wireValue,
)

/** Strict decode of the durable run-invariants artifact. */
@OpenBoundaryMap("Feature-task-runtime run-invariants decode from the durable workflow-artifact map")
fun featureTaskRuntimeRunInvariantsFromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeRunInvariants =
  FeatureTaskRuntimeRunInvariants(
    specReference = raw.requireInvariantStringField("spec_reference"),
    featureSize = raw.requireFeatureSizeField("feature_size"),
    acceptanceCriteria = raw.requireInvariantStringListField("acceptance_criteria"),
    mandatesAndOverrides = raw.requireInvariantStringListField("mandates_and_overrides"),
    codeReviewMode = raw.requireCodeReviewModeField("code_review_mode"),
  )

private fun Map<String, Any?>.requireInvariantStringField(key: String): String {
  val value = this[key] ?: runInvariantSchemaError("Feature-task-runtime artifact map is missing field '$key'.")
  return (value as? String)?.takeIf(String::isNotBlank)
    ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must decode to a non-blank string.")
}

private fun Map<String, Any?>.requireInvariantStringListField(key: String): List<String> {
  val value = this[key]
    ?: runInvariantSchemaError("Feature-task-runtime artifact map is missing required list field '$key'.")
  val list = value as? List<*>
    ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must decode to a list.")
  return list.map { element ->
    element as? String
      ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must contain strings.")
  }
}

private fun Map<String, Any?>.requireFeatureSizeField(key: String): FeatureTaskRuntimeFeatureSize {
  val rawValue = requireInvariantStringField(key)
  return try {
    FeatureTaskRuntimeFeatureSize.fromWire(rawValue)
  } catch (_: IllegalArgumentException) {
    runInvariantSchemaError("Feature-task-runtime artifact field '$key' must be one of SMALL, MEDIUM, LARGE.")
  }
}

private fun Map<String, Any?>.requireCodeReviewModeField(key: String): CodeReviewExecutionMode = try {
  CodeReviewExecutionMode.fromWire(requireInvariantStringField(key))
} catch (_: IllegalArgumentException) {
  runInvariantSchemaError("Feature-task-runtime artifact field '$key' must be one of auto, inline, delegated.")
}

private fun runInvariantSchemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)
