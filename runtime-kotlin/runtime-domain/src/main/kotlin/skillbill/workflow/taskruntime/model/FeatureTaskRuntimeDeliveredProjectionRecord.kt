package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * One delivered handoff envelope, recorded per consumer phase and iteration. Carries only the
 * projection envelope: there is no field on this record that can hold a complete phase output.
 */
data class FeatureTaskRuntimeDeliveredProjectionRecord(
  val workflowId: String,
  val consumerPhaseId: String,
  val iteration: Int,
  val envelope: FeatureTaskRuntimeHandoffEnvelope,
  val sourceProducerIterations: List<FeatureTaskRuntimeProducerIteration> =
    envelope.projections.map { it.producerIteration }.distinct(),
) {
  val repositoryCheckpointFingerprint: String =
    envelope.repositoryCheckpoint?.fingerprint ?: "not_required:$consumerPhaseId"

  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeDeliveredProjectionRecord.workflowId must be non-blank." }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeDeliveredProjectionRecord.consumerPhaseId must be non-blank."
    }
    require(iteration >= 1) {
      "FeatureTaskRuntimeDeliveredProjectionRecord.iteration must be >= 1, was $iteration."
    }
    require(sourceProducerIterations.toSet() == envelope.projections.map { it.producerIteration }.toSet()) {
      "FeatureTaskRuntimeDeliveredProjectionRecord source producer identities must match its delivered projections."
    }
    require(envelope.consumerPhaseId == consumerPhaseId) {
      "FeatureTaskRuntimeDeliveredProjectionRecord for '$consumerPhaseId' carries an envelope addressed to " +
        "'${envelope.consumerPhaseId}'."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime delivered-projection record at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "delivered_projection",
    "workflow_id" to workflowId,
    "consumer_phase_id" to consumerPhaseId,
    "consumer_delivery_iteration" to iteration,
    "source_producer_iterations" to sourceProducerIterations.map {
      mapOf("phase_id" to it.phaseId, "iteration" to it.iteration)
    },
    "repository_checkpoint" to mapOf("fingerprint" to repositoryCheckpointFingerprint),
    "handoff_envelope" to envelope.toEnvelopeMap(),
  )

  companion object {
    @OpenBoundaryMap("Feature-task-runtime delivered-projection decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDeliveredProjectionRecord {
      requireExactDeliveredProjectionFields(raw)
      requireSupportedPersistenceContract(raw)
      requireDeliveredProjectionRecordKind(raw)
      val record = decodeDeliveredProjection(raw)
      requireMatchingCheckpoint(raw, record)
      return record
    }

    private fun requireSupportedPersistenceContract(raw: Map<String, Any?>) {
      val contractVersion = raw["contract_version"] as? String ?: missing("contract_version")
      if (contractVersion != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered projection uses unsupported persistence contract version " +
            "'$contractVersion'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }

    private fun requireDeliveredProjectionRecordKind(raw: Map<String, Any?>) {
      if (raw["record_kind"] != "delivered_projection") {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime prompt-facing persistence record must have kind 'delivered_projection'; " +
            "private evidence cannot be read through this API.",
        )
      }
    }

    private fun decodeDeliveredProjection(raw: Map<String, Any?>) = FeatureTaskRuntimeDeliveredProjectionRecord(
      workflowId = raw["workflow_id"] as? String ?: missing("workflow_id"),
      consumerPhaseId = raw["consumer_phase_id"] as? String ?: missing("consumer_phase_id"),
      iteration = (raw["consumer_delivery_iteration"] as? Number)?.toInt()
        ?: missing("consumer_delivery_iteration"),
      envelope = FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap(
        JsonSupport.anyToStringAnyMap(raw["handoff_envelope"])
          // Named explicitly: the private phase-output artifact is never an acceptable substitute
          // for the delivered projection, so an absent envelope is a hard decode failure.
          ?: missing("handoff_envelope"),
      ),
      sourceProducerIterations = decodeSourceProducerIterations(raw),
    )

    private fun decodeSourceProducerIterations(raw: Map<String, Any?>): List<FeatureTaskRuntimeProducerIteration> =
      (raw["source_producer_iterations"] as? List<*>)
        ?.map { identity ->
          val map = JsonSupport.anyToStringAnyMap(identity) ?: missing("source_producer_iterations")
          FeatureTaskRuntimeProducerIteration(
            phaseId = map["phase_id"] as? String ?: missing("source_producer_iterations.phase_id"),
            iteration = (map["iteration"] as? Number)?.toInt()
              ?: missing("source_producer_iterations.iteration"),
          )
        } ?: missing("source_producer_iterations")

    private fun requireMatchingCheckpoint(
      raw: Map<String, Any?>,
      record: FeatureTaskRuntimeDeliveredProjectionRecord,
    ) {
      val checkpoint = JsonSupport.anyToStringAnyMap(raw["repository_checkpoint"])
        ?: missing("repository_checkpoint")
      val persistedFingerprint = checkpoint["fingerprint"] as? String ?: missing("repository_checkpoint.fingerprint")
      if (persistedFingerprint != record.repositoryCheckpointFingerprint) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered projection checkpoint identity does not match its validated envelope; " +
            "restart the consumer phase from current repository state.",
        )
      }
    }

    private fun requireExactDeliveredProjectionFields(raw: Map<String, Any?>) {
      val expected = setOf(
        "contract_version",
        "record_kind",
        "workflow_id",
        "consumer_phase_id",
        "consumer_delivery_iteration",
        "source_producer_iterations",
        "repository_checkpoint",
        "handoff_envelope",
      )
      val unexpected = raw.keys - expected
      if (unexpected.isNotEmpty()) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered-projection record contains unsupported fields; " +
            "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }

    // Single throw seam so the strict decoder stays within the throw-count budget.
    private fun missing(field: String): Nothing = throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime delivered-projection record is missing field '$field'; " +
        "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
    )
  }
}
