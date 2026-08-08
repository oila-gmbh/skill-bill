package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REJECTION_MEASUREMENT_CONTRACT_VERSION
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

/**
 * Privacy-safe accounting for an attempt the schema gate REJECTED. The projection measurement above
 * records only projections that passed, so without this type an exhausted fix loop is invisible to
 * everyone except the operator reading the block — which is how a recurring over-length receipt field
 * reached users before anyone could see it happening.
 *
 * The same payload-free boundary the retry path enforces applies here: the pointer and the
 * classification are emitted, never the offending value. [observedLength] is a length, not content.
 */
data class FeatureTaskRuntimeRejectionMeasurement(
  val workflowId: String,
  val phaseId: String,
  val iteration: Int,
  val rule: String,
  val pointerPath: String,
  val violationClass: FeatureTaskRuntimeRejectionViolationClass,
  val declaredCap: Int? = null,
  val observedLength: Int? = null,
  val exhaustedFixLoop: Boolean = false,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.workflowId must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimeRejectionMeasurement.iteration must be >= 1." }
    require(rule.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.rule must be non-blank." }
    require(pointerPath.isNotBlank()) {
      "FeatureTaskRuntimeRejectionMeasurement.pointerPath must be non-blank."
    }
    require(declaredCap == null || declaredCap >= 0) {
      "FeatureTaskRuntimeRejectionMeasurement.declaredCap must be non-negative."
    }
    require(observedLength == null || observedLength >= 0) {
      "FeatureTaskRuntimeRejectionMeasurement.observedLength must be non-negative."
    }
  }

  @OpenBoundaryMap("Content-free feature-task-runtime rejection measurement telemetry seam")
  fun toTelemetryMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_REJECTION_MEASUREMENT_CONTRACT_VERSION,
    "workflow_id" to workflowId,
    "phase_id" to phaseId,
    "iteration" to iteration,
    "rule" to rule,
    "pointer_path" to pointerPath,
    "violation_class" to violationClass.wireValue,
    "exhausted_fix_loop" to exhaustedFixLoop,
  ).apply {
    declaredCap?.let { put("declared_cap", it) }
    observedLength?.let { put("observed_length", it) }
  }
}

/**
 * Why the gate rejected, at the coarsest granularity that still separates repairable field errors from
 * an unparseable response. [LENGTH] is the one this vocabulary exists to make countable.
 */
enum class FeatureTaskRuntimeRejectionViolationClass(val wireValue: String) {
  LENGTH("length"),
  MISSING("missing"),
  TYPE("type"),
  CONST("const"),
  MALFORMED("malformed"),
  OTHER("other"),
}

// The validator groups digits past a thousand ("4,096"), so the separator belongs to the number.
private val REJECTION_LENGTH_PATTERN =
  Regex("""(?:must be|allows) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

/**
 * The declared cap a validator reason names, or null when it names none. Reading the figure from the
 * message rather than from a constant is what lets one classifier serve every bounded field regardless
 * of its cap.
 */
fun featureTaskRuntimeRejectionCapOf(validationReason: String): Int? =
  REJECTION_LENGTH_PATTERN.find(validationReason)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

/**
 * Classifies a validator reason into the countable vocabulary. Ordered most-specific first: a length
 * violation is recognised by its stated cap (or a bare `maxLength` mention) before the broader type and
 * shape phrasings, which would otherwise absorb it — "must be at most N characters" also matches "must
 * be a".
 */
fun featureTaskRuntimeRejectionViolationClassOf(validationReason: String): FeatureTaskRuntimeRejectionViolationClass =
  when {
    featureTaskRuntimeRejectionCapOf(validationReason) != null || validationReason.contains("maxLength") ->
      FeatureTaskRuntimeRejectionViolationClass.LENGTH
    validationReason.contains("is malformed") || validationReason.contains("must be an object") ->
      FeatureTaskRuntimeRejectionViolationClass.MALFORMED
    validationReason.contains("must be the constant value") ->
      FeatureTaskRuntimeRejectionViolationClass.CONST
    validationReason.contains("is missing") || validationReason.contains("is not defined in the schema") ->
      FeatureTaskRuntimeRejectionViolationClass.MISSING
    validationReason.contains("expected") || validationReason.contains("must be a") ->
      FeatureTaskRuntimeRejectionViolationClass.TYPE
    else -> FeatureTaskRuntimeRejectionViolationClass.OTHER
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
