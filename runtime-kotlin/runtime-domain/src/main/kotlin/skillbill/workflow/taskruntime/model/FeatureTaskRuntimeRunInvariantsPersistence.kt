package skillbill.workflow.taskruntime.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode

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
  "agent_addon_selection" to agentAddonSelection.entries.map { entry ->
    linkedMapOf(
      "slug" to entry.slug,
      "source_identity" to entry.sourceIdentity,
      "content_sha256" to entry.contentSha256,
    )
  },
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
    agentAddonSelection = raw.optionalAgentAddonSelection(),
  )

private fun Map<String, Any?>.optionalAgentAddonSelection(): AgentAddonSelection {
  val value = this["agent_addon_selection"] ?: return AgentAddonSelection()
  val entries = value as? List<*>
    ?: runInvariantSchemaError("Feature-task-runtime artifact field 'agent_addon_selection' must decode to a list.")
  return try {
    AgentAddonSelection(entries.mapIndexed { index, rawEntry ->
      val entry = rawEntry as? Map<*, *>
        ?: runInvariantSchemaError("Agent add-on selection entry $index must decode to a map.")
      val keys = entry.keys.map { it as? String ?: runInvariantSchemaError("Agent add-on selection entry $index has a non-string field.") }.toSet()
      val expected = setOf("slug", "source_identity", "content_sha256")
      if (keys != expected) runInvariantSchemaError("Agent add-on selection entry $index fields must be exactly ${expected.sorted()}.")
      PersistedAgentAddonSelectionEntry(
        slug = entry["slug"] as? String ?: runInvariantSchemaError("Agent add-on selection entry $index slug is invalid."),
        sourceIdentity = entry["source_identity"] as? String
          ?: runInvariantSchemaError("Agent add-on selection entry $index source_identity is invalid."),
        contentSha256 = entry["content_sha256"] as? String
          ?: runInvariantSchemaError("Agent add-on selection entry $index content_sha256 is invalid."),
      )
    })
  } catch (error: IllegalArgumentException) {
    throw InvalidWorkflowStateSchemaError("Agent add-on selection is invalid: ${error.message}", error)
  }
}

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
