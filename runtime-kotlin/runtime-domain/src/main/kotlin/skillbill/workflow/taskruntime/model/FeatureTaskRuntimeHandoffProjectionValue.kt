package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.model.ValidationDepth

/**
 * Compact-reference kinds. [runtimeResolvable] marks the kinds a runtime-owned deterministic
 * operation can dereference; the others are plain identifiers the consumer reads as-is. No kind
 * introduces arbitrary model-driven retrieval.
 */
enum class FeatureTaskRuntimeCompactReferenceKind(val wireValue: String, val runtimeResolvable: Boolean) {
  PRIVATE_EVIDENCE_ARTIFACT("private_evidence_artifact", true),
  REPOSITORY_PATH("repository_path", true),
  REPOSITORY_CHECKPOINT("repository_checkpoint", false),
  ACCEPTANCE_CRITERION_REF("acceptance_criterion_ref", false),
  REPAIR_ITEM_ID("repair_item_id", false),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeCompactReferenceKind = entries.firstOrNull { it.wireValue == value }
      ?: unrecognizedHandoffWireValue("compact reference kind", value)
  }
}

/** Closed set of projection value shapes. Nothing here can carry a nested open map or raw blob. */
sealed interface FeatureTaskRuntimeHandoffProjectionValue {
  val utf8ByteSize: Int
  val itemCount: Int

  data class Text(val text: String) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = text.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }

  data class TextList(val items: List<String>) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = items.sumOf { it.toByteArray(Charsets.UTF_8).size }
    override val itemCount: Int get() = items.size
  }

  data class CompactReference(
    val kind: FeatureTaskRuntimeCompactReferenceKind,
    val value: String,
  ) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = value.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }
}

/** Field names a projection must never carry, because each names an unbounded raw-context channel. */
val FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES: Set<String> = setOf(
  "upstream_outputs_by_phase_id",
  "raw_payload",
  "payload",
  "raw_prompt",
  "prompt",
  "transcript",
  "tool_output",
  "log",
  "logs",
  "source_body",
  "diff_body",
  "telemetry",
)

internal val PROJECTION_NAME_PATTERN = Regex("^[a-z][a-z0-9_]*$")

data class FeatureTaskRuntimeHandoffProjectionField(
  val name: String,
  val value: FeatureTaskRuntimeHandoffProjectionValue,
) {
  init {
    require(PROJECTION_NAME_PATTERN.matches(name)) {
      "FeatureTaskRuntimeHandoffProjectionField.name must match ${PROJECTION_NAME_PATTERN.pattern}, was '$name'."
    }
    require(name !in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES) {
      "FeatureTaskRuntimeHandoffProjectionField.name '$name' is a forbidden raw-context field."
    }
  }
}
