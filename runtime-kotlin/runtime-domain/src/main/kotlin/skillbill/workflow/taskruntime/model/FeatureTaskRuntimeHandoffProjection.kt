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

/** One validated projection actually delivered to a consumer phase. */
data class FeatureTaskRuntimeHandoffProjection(
  val projectionName: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  val producerIteration: FeatureTaskRuntimeProducerIteration =
    FeatureTaskRuntimeProducerIteration(
      phaseId = (sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)?.producingPhaseId
        ?: "runtime",
      iteration = 1,
    ),
) {
  /**
   * Exact UTF-8 size of the representation delivered to the consumer. Prompt-visible projections
   * use their canonical line rendering; private projections use their durable wire representation.
   */
  val utf8ByteSize: Int
    get() = canonicalDeliveredRendering.toByteArray(Charsets.UTF_8).size
  val itemCount: Int get() = fields.sumOf { it.value.itemCount }
  val canonicalDeliveredRendering: String
    get() = if (promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE) {
      buildString {
        if (sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput) {
          appendLine("### from: ${sourceRef.producingPhaseId}")
        } else {
          appendLine("### $projectionName (${sourceRef.wireValue})")
        }
        fields.forEach { field ->
          val singleReceipt = fields.size == 1 &&
            field.name == "phase_output_receipt"
          when (val value = field.value) {
            is FeatureTaskRuntimeHandoffProjectionValue.Text -> {
              val text = value.text.escapeProjectionLineBreaks()
              if (singleReceipt) appendLine(text) else appendLine("${field.name}: $text")
            }
            is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
              appendLine("${field.name}:")
              value.items.forEach { item -> appendLine("  - ${item.escapeProjectionLineBreaks()}") }
            }
            is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ->
              appendLine("${field.name}: ${value.kind.wireValue}=${value.value}")
          }
        }
      }
    } else {
      JsonSupport.mapToJsonString(toEnvelopeMap())
    }

  @OpenBoundaryMap("Feature-task-runtime handoff projection at the durable envelope wire seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf(
    "projection_name" to projectionName,
    "source_ref" to sourceRef.wireValue,
    "projection_contract_id" to projectionContractId,
    "projection_contract_version" to projectionContractVersion,
    "prompt_visibility" to promptVisibility.wireValue,
    "producer_iteration" to mapOf(
      "phase_id" to producerIteration.phaseId,
      "iteration" to producerIteration.iteration,
    ),
    "fields" to fields.map { field ->
      linkedMapOf<String, Any?>("name" to field.name).apply {
        when (val value = field.value) {
          is FeatureTaskRuntimeHandoffProjectionValue.Text -> {
            put("kind", "text")
            put("text", value.text)
          }
          is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
            put("kind", "text_list")
            put("items", value.items)
          }
          is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> {
            put("kind", "compact_reference")
            put("reference_kind", value.kind.wireValue)
            put("reference_value", value.value)
          }
        }
      }
    },
  )
}

private fun String.escapeProjectionLineBreaks(): String = replace("\r", "\\r").replace("\n", "\\n")
