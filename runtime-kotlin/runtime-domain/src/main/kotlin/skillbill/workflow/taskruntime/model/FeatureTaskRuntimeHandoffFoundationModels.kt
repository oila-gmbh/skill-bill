package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION

const val FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE: String =
  "restart the active run or use the documented out-of-band migration procedure"

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

/**
 * Privacy-safe shared-evidence accounting. Carries identifiers, the resolve outcome, and bounded
 * index counters only — never file paths, diff content, or prompt bodies — so reuse rate is
 * computable from the emitted fields alone.
 */
data class FeatureTaskRuntimeSharedEvidenceMeasurement(
  val workflowId: String,
  val checkpointFingerprint: String,
  val consumerPhaseId: String,
  val outcome: FeatureTaskRuntimeSharedEvidenceOutcome,
  val fileIndexCount: Int,
  val hunkIndexCount: Int,
) {
  init {
    require(workflowId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.workflowId must be non-blank."
    }
    require(checkpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.checkpointFingerprint must be non-blank."
    }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.consumerPhaseId must be non-blank."
    }
    require(fileIndexCount >= 0 && hunkIndexCount >= 0) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement counts must be non-negative."
    }
  }

  @OpenBoundaryMap("Content-free feature-task-runtime shared-evidence measurement telemetry seam")
  fun toTelemetryMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
    "workflow_id" to workflowId,
    "checkpoint_fingerprint" to checkpointFingerprint,
    "consumer_phase_id" to consumerPhaseId,
    "outcome" to outcome.wireValue,
    "file_index_count" to fileIndexCount,
    "hunk_index_count" to hunkIndexCount,
  )
}

enum class FeatureTaskRuntimeSharedEvidenceOutcome(val wireValue: String) {
  DERIVATION("derivation"),
  REUSE("reuse"),
  CHECKPOINT_CHANGE_REDERIVATION("checkpoint_change_rederivation"),
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
