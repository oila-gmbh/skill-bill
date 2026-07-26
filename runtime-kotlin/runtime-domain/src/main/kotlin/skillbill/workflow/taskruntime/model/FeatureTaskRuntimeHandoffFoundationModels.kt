package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * Immutable identity of the exact producer attempt from which a consumer projection was derived.
 * A phase id alone is insufficient on retries and backward edges.
 */
data class FeatureTaskRuntimeProducerIteration(
  val phaseId: String,
  val iteration: Int,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeProducerIteration.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimeProducerIteration.iteration must be >= 1." }
  }
}

/**
 * Privacy-safe projection accounting. This type intentionally has no prompt, payload, source,
 * receipt, diff, log, or arbitrary metadata field.
 */
data class FeatureTaskRuntimeProjectionMeasurement(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String,
  val projectedUtf8Bytes: Int,
  val projectedCollectionItems: Int,
  val estimatedTokens: Int,
  val privateEvidenceUtf8Bytes: Int,
  val deliveredProjectionUtf8Bytes: Int,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification? = null,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeProjectionMeasurement.workflowId must be non-blank." }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.consumerPhaseId must be non-blank."
    }
    require(projectionContractId.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.projectionContractId must be non-blank."
    }
    require(repositoryCheckpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.repositoryCheckpointFingerprint must be non-blank."
    }
    require(
      projectedUtf8Bytes >= 0 &&
        projectedCollectionItems >= 0 &&
        estimatedTokens >= 0 &&
        privateEvidenceUtf8Bytes >= 0 &&
        deliveredProjectionUtf8Bytes >= 0,
    ) {
      "FeatureTaskRuntimeProjectionMeasurement counts must be non-negative."
    }
  }

  @OpenBoundaryMap("Content-free feature-task-runtime projection measurement telemetry seam")
  fun toTelemetryMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION,
    "workflow_id" to workflowId,
    "consumer_phase_id" to consumerPhaseId,
    "projection_contract_id" to projectionContractId,
    "producer_iteration" to mapOf(
      "phase_id" to producerIteration.phaseId,
      "iteration" to producerIteration.iteration,
    ),
    "repository_checkpoint_fingerprint" to repositoryCheckpointFingerprint,
    "projected_utf8_bytes" to projectedUtf8Bytes,
    "projected_collection_items" to projectedCollectionItems,
    "estimated_tokens" to estimatedTokens,
    "private_evidence_utf8_bytes" to privateEvidenceUtf8Bytes,
    "delivered_projection_utf8_bytes" to deliveredProjectionUtf8Bytes,
  ).apply {
    failureClassification?.let { put("failure_classification", it.wireValue) }
  }
}

enum class FeatureTaskRuntimeProjectionFailureClassification(val wireValue: String) {
  INVALID_CONTRACT("invalid_contract"),
  UNSUPPORTED_VERSION("unsupported_version"),
  UNPROJECTABLE_SOURCE("unprojectable_source"),
  BUDGET_OVERFLOW("budget_overflow"),
  STALE_CHECKPOINT("stale_checkpoint"),
  STALE_PRODUCER_ITERATION("stale_producer_iteration"),
  SIBLING_CONTEXT("sibling_context"),
}

/**
 * Diagnostic-only durable source artifact. Prompt-facing persistence APIs accept the distinct
 * [FeatureTaskRuntimeDeliveredProjectionRecord] type and cannot decode this record.
 */
data class FeatureTaskRuntimePrivatePhaseEvidenceRecord(
  val workflowId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint,
  val phaseOutput: String,
) {
  init {
    require(workflowId.isNotBlank()) { "Private phase evidence workflowId must be non-blank." }
    require(phaseOutput.isNotBlank()) { "Private phase evidence phaseOutput must be non-blank." }
  }

  @OpenBoundaryMap("Private feature-task-runtime evidence at the durable persistence wire seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "private_phase_evidence",
    "workflow_id" to workflowId,
    "producer_phase_id" to producerIteration.phaseId,
    "producer_iteration" to producerIteration.iteration,
    "repository_checkpoint" to repositoryCheckpoint.toEnvelopeMap(),
    "phase_output" to phaseOutput,
  )

  companion object {
    @OpenBoundaryMap("Strict private feature-task-runtime evidence decode from durable persistence")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePrivatePhaseEvidenceRecord {
      val expected = setOf(
        "contract_version",
        "record_kind",
        "workflow_id",
        "producer_phase_id",
        "producer_iteration",
        "repository_checkpoint",
        "phase_output",
      )
      if (raw.keys != expected ||
        raw["contract_version"] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION ||
        raw["record_kind"] != "private_phase_evidence"
      ) {
        throw InvalidWorkflowStateSchemaError(
          "Private feature-task-runtime evidence is incompatible with persistence contract " +
            "$FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION; restart or explicitly migrate the workflow.",
        )
      }
      val checkpoint = JsonSupport.anyToStringAnyMap(raw["repository_checkpoint"])
        ?: invalidPrivateEvidence()
      return FeatureTaskRuntimePrivatePhaseEvidenceRecord(
        workflowId = raw["workflow_id"] as? String ?: invalidPrivateEvidence(),
        producerIteration = FeatureTaskRuntimeProducerIteration(
          phaseId = raw["producer_phase_id"] as? String ?: invalidPrivateEvidence(),
          iteration = (raw["producer_iteration"] as? Number)?.toInt() ?: invalidPrivateEvidence(),
        ),
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = checkpoint["fingerprint"] as? String ?: invalidPrivateEvidence(),
          baseRef = checkpoint["base_ref"] as? String,
          headRef = checkpoint["head_ref"] as? String,
          workingTreeOwnedPaths = (checkpoint["working_tree_owned_paths"] as? List<*>)
            ?.map { it as? String ?: invalidPrivateEvidence() }
            .orEmpty(),
        ),
        phaseOutput = raw["phase_output"] as? String ?: invalidPrivateEvidence(),
      )
    }

    private fun invalidPrivateEvidence(): Nothing = throw InvalidWorkflowStateSchemaError(
      "Private feature-task-runtime evidence is malformed; restart or explicitly migrate the workflow.",
    )
  }
}
