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
 * The durable, prompt-visible handoff envelope: named typed projections and compact references only.
 * There is intentionally no generic upstream-payload map, raw payload, prompt, transcript, tool
 * output, log, source body, diff body, or telemetry field anywhere in this shape.
 */
data class FeatureTaskRuntimeHandoffEnvelope(
  val consumerPhaseId: String,
  val projections: List<FeatureTaskRuntimeHandoffProjection> = emptyList(),
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val contractVersion: String = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
) {
  init {
    require(consumerPhaseId.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.consumerPhaseId must be non-blank." }
    require(contractVersion.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.contractVersion must be non-blank." }
    val names = projections.map { it.projectionName }
    require(names.distinct().size == names.size) {
      "FeatureTaskRuntimeHandoffEnvelope for '$consumerPhaseId' contains duplicate projection names."
    }
  }

  /** Only the prompt-visible projections reach prompt composition; private ones never render. */
  val promptVisibleProjections: List<FeatureTaskRuntimeHandoffProjection>
    get() = projections.filter { it.promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE }

  @OpenBoundaryMap("Feature-task-runtime handoff envelope at the durable workflow-artifact seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "consumer_phase_id" to consumerPhaseId,
    "projections" to projections.map { it.toEnvelopeMap() },
  ).apply {
    repositoryCheckpoint?.let { put("repository_checkpoint", it.toEnvelopeMap()) }
  }

  companion object {
    @OpenBoundaryMap("Feature-task-runtime handoff envelope decode from the durable workflow-artifact map")
    fun fromEnvelopeMap(raw: Map<String, Any?>): FeatureTaskRuntimeHandoffEnvelope = FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = raw.requireString("consumer_phase_id"),
      projections = (raw["projections"] as? List<*>).orEmpty().map { projectionFromWire(it) },
      repositoryCheckpoint = (raw["repository_checkpoint"] as? Map<*, *>)?.let { checkpoint ->
        FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = checkpoint.requireString("fingerprint"),
          baseRef = checkpoint["base_ref"] as? String,
          headRef = checkpoint["head_ref"] as? String,
          workingTreeOwnedPaths = (checkpoint["working_tree_owned_paths"] as? List<*>).orEmpty()
            .map { it.requireDecodedString("working_tree_owned_paths") },
        )
      },
      contractVersion = raw.requireString("contract_version"),
    )

    private fun projectionFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjection {
      val projection = raw as? Map<*, *> ?: decodeError("projections entries must be objects.")
      return FeatureTaskRuntimeHandoffProjection(
        projectionName = projection.requireString("projection_name"),
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.fromWire(projection.requireString("source_ref")),
        projectionContractId = projection.requireString("projection_contract_id"),
        projectionContractVersion = projection.requireString("projection_contract_version"),
        promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility
          .fromWire(projection.requireString("prompt_visibility")),
        producerIteration = (projection["producer_iteration"] as? Map<*, *>)?.let {
          FeatureTaskRuntimeProducerIteration(
            phaseId = it.requireString("phase_id"),
            iteration = (it["iteration"] as? Number)?.toInt()
              ?: decodeError("field 'producer_iteration.iteration' must be an integer."),
          )
        } ?: decodeError("field 'producer_iteration' must decode to an object."),
        fields = (projection["fields"] as? List<*>).orEmpty().map(::fieldFromWire),
      )
    }

    private fun fieldFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjectionField {
      val field = raw as? Map<*, *> ?: decodeError("projection fields entries must be objects.")
      val name = field.requireString("name")
      return FeatureTaskRuntimeHandoffProjectionField(
        name = name,
        value = when (val kind = field.requireString("kind")) {
          "text" -> FeatureTaskRuntimeHandoffProjectionValue.Text(field.requireString("text"))
          "text_list" -> FeatureTaskRuntimeHandoffProjectionValue.TextList(
            (field["items"] as? List<*>).orEmpty().map { it.requireDecodedString("items") },
          )
          "compact_reference" -> FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.fromWire(field.requireString("reference_kind")),
            value = field.requireString("reference_value"),
          )
          else -> decodeError("projection field '$name' has unknown value kind '$kind'.")
        },
      )
    }

    private fun decodeError(detail: String): Nothing =
      throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(sourceLabel = "<wire>", reason = detail)

    private fun Map<*, *>.requireString(key: String): String = (this[key] as? String)?.takeIf(String::isNotBlank)
      ?: decodeError("field '$key' must decode to a non-blank string.")

    private fun Any?.requireDecodedString(key: String): String =
      this as? String ?: decodeError("field '$key' must contain strings.")
  }
}

