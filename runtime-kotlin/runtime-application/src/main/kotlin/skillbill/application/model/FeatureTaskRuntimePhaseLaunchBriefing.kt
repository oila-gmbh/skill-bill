package skillbill.application.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * The typed, fully-assembled launch briefing for one phase: the three handoff layers as typed
 * fields plus a deterministic serialized [briefingText]. The non-empty run-invariant fields are a
 * construction guarantee, so layer 1 is always present.
 */
data class FeatureTaskRuntimePhaseLaunchBriefing(
  val phaseId: String,
  val specReference: String,
  val featureSize: String,
  val acceptanceCriteria: List<String>,
  val mandatesAndOverrides: List<String>,
  val upstreamOutputsByPhaseId: Map<String, String>,
  val derivedContextKeys: List<String>,
  val briefingText: String,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseLaunchBriefing.phaseId must be non-blank." }
    require(specReference.isNotBlank()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.specReference must be non-blank; run-invariants are unconditional."
    }
    require(featureSize.isNotBlank()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.featureSize must be non-blank; run-invariants are unconditional."
    }
    require(acceptanceCriteria.isNotEmpty()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.acceptanceCriteria must be non-empty; run-invariants are unconditional."
    }
    require(briefingText.isNotBlank()) { "FeatureTaskRuntimePhaseLaunchBriefing.briefingText must be non-blank." }
  }

  /** Serializes the briefing for the durable artifact store, preserving all three handoff layers. */
  @OpenBoundaryMap("Feature-task-runtime per-phase launch briefing artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "phase_id" to phaseId,
    "spec_reference" to specReference,
    "feature_size" to featureSize,
    "acceptance_criteria" to acceptanceCriteria,
    "mandates_and_overrides" to mandatesAndOverrides,
    "upstream_outputs_by_phase_id" to LinkedHashMap(upstreamOutputsByPhaseId),
    "derived_context_keys" to derivedContextKeys,
    "briefing_text" to briefingText,
  )

  companion object {
    /** Strict decode of one persisted briefing map; loud-fails on any missing/malformed field. */
    @OpenBoundaryMap("Feature-task-runtime per-phase launch briefing decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLaunchBriefing =
      FeatureTaskRuntimePhaseLaunchBriefing(
        phaseId = raw.requireStringField("phase_id"),
        specReference = raw.requireStringField("spec_reference"),
        featureSize = raw.requireStringField("feature_size"),
        acceptanceCriteria = raw.requireStringListField("acceptance_criteria"),
        mandatesAndOverrides = raw.requireStringListField("mandates_and_overrides"),
        upstreamOutputsByPhaseId = raw.requireStringMapField("upstream_outputs_by_phase_id"),
        derivedContextKeys = raw.requireStringListField("derived_context_keys"),
        briefingText = raw.requireStringField("briefing_text"),
      )

    // Single throw seam so each strict decoder stays within the throw-count budget.
    private fun schemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

    private fun Map<String, Any?>.requireStringField(key: String): String {
      val value = this[key] ?: schemaError("Feature-task-runtime briefing artifact map is missing field '$key'.")
      return (value as? String)?.takeIf(String::isNotBlank)
        ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to a non-blank string.")
    }

    private fun Map<String, Any?>.requireStringListField(key: String): List<String> {
      val list = (if (containsKey(key)) this[key] else schemaError(missingMessage(key, "list"))) as? List<*>
        ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to a list.")
      return list.map { element ->
        element as? String ?: schemaError("Feature-task-runtime briefing artifact field '$key' must contain strings.")
      }
    }

    private fun Map<String, Any?>.requireStringMapField(key: String): Map<String, String> {
      val rawValue = if (containsKey(key)) this[key] else schemaError(missingMessage(key, "map"))
      val map = JsonSupport.anyToStringAnyMap(rawValue)
        ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to a string-keyed map.")
      return map.entries.associate { (mapKey, mapValue) ->
        mapKey to (
          mapValue as? String
            ?: schemaError("Feature-task-runtime briefing artifact field '$key' must map to string values.")
          )
      }
    }

    private fun missingMessage(key: String, kind: String): String =
      "Feature-task-runtime briefing artifact map is missing required $kind field '$key'."
  }
}
