package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * Durable, append-only quarantine evidence store. When a launch seam rejects an upstream producer's
 * durable record (a legacy or drifted bounded planning projection), the runtime appends the rejected
 * record here as PRIVATE evidence and re-enters the producing phase under a bounded regeneration cap.
 *
 * This key is deliberately excluded from every prompt and briefing path: it is never resolved into an
 * upstream projection, so no rejected-record byte reaches an agent. The runtime only ever appends; it
 * never mutates or deletes a prior entry. Only out-of-band operator action may remove evidence.
 */
const val FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY: String =
  "feature_task_runtime_quarantined_records"

/** Wire value of the quarantine contract version, mirrored by the canonical quarantine schema. */
const val FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION: String = "0.1"

/** Typed classes of launch-seam rejection that trigger quarantine; mirror the schema enum. */
const val QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION: String = "planning_projection_schema"
const val QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE: String = "handoff_envelope_schema"

/**
 * One quarantined durable record. Names the producing phase (which will be regenerated), the
 * consuming phase whose launch seam rejected it, the rejected producing iteration, the typed
 * rejection class and bounded detail, the per-producer regeneration attempt at quarantine, the
 * consuming-phase iteration at quarantine, and the verbatim rejected payload retained for diagnostics.
 */
data class FeatureTaskRuntimeQuarantineEntry(
  val producingPhaseId: String,
  val consumingPhaseId: String,
  val producingIteration: Int,
  val rejectionClass: String,
  val rejectionDetail: String,
  val regenerationAttempt: Int,
  val quarantinedAtIteration: Int,
  val rejectedRecordPayload: String,
) {
  init {
    require(producingPhaseId.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.producingPhaseId must be non-blank." }
    require(consumingPhaseId.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.consumingPhaseId must be non-blank." }
    require(producingIteration >= 1) { "FeatureTaskRuntimeQuarantineEntry.producingIteration must be >= 1." }
    require(rejectionClass.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.rejectionClass must be non-blank." }
    require(rejectionDetail.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.rejectionDetail must be non-blank." }
    require(regenerationAttempt >= 1) { "FeatureTaskRuntimeQuarantineEntry.regenerationAttempt must be >= 1." }
    require(quarantinedAtIteration >= 1) { "FeatureTaskRuntimeQuarantineEntry.quarantinedAtIteration must be >= 1." }
  }

  @OpenBoundaryMap("Feature-task-runtime quarantine entry artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "producing_phase_id" to producingPhaseId,
    "consuming_phase_id" to consumingPhaseId,
    "producing_iteration" to producingIteration,
    "rejection_class" to rejectionClass,
    "rejection_detail" to rejectionDetail,
    "regeneration_attempt" to regenerationAttempt,
    "quarantined_at_iteration" to quarantinedAtIteration,
    "rejected_record_payload" to rejectedRecordPayload,
  )

  /** A stable identifier for this quarantined record, used in cap-exhaustion block reasons. */
  fun recordIdentifier(): String = "$producingPhaseId#$producingIteration"

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime quarantine entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeQuarantineEntry = FeatureTaskRuntimeQuarantineEntry(
      producingPhaseId = raw.requireStringField("producing_phase_id"),
      consumingPhaseId = raw.requireStringField("consuming_phase_id"),
      producingIteration = raw.requireIntField("producing_iteration"),
      rejectionClass = raw.requireStringField("rejection_class"),
      rejectionDetail = raw.requireStringField("rejection_detail"),
      regenerationAttempt = raw.requireIntField("regeneration_attempt"),
      quarantinedAtIteration = raw.requireIntField("quarantined_at_iteration"),
      rejectedRecordPayload = raw.requireStringField("rejected_record_payload"),
    )
  }
}

/**
 * Encodes the append-only quarantine list into the durable wire map the canonical quarantine schema
 * validates: a `contract_version` and an ordered `entries` array.
 */
@OpenBoundaryMap("Feature-task-runtime quarantine record artifact map at the durable workflow-artifact seam")
fun featureTaskRuntimeQuarantineRecordToWire(entries: List<FeatureTaskRuntimeQuarantineEntry>): Map<String, Any?> =
  linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION,
    "entries" to entries.map { it.toArtifactMap() },
  )

private fun quarantineSchemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

/** Strict decode of the durable quarantine record; loud-fails on a malformed artifact. */
@OpenBoundaryMap("Feature-task-runtime quarantine record decode from the durable workflow-artifact map")
fun featureTaskRuntimeQuarantineEntriesFromWire(raw: Any?): List<FeatureTaskRuntimeQuarantineEntry> {
  val map = raw as? Map<*, *>
    ?: quarantineSchemaError("Feature-task-runtime quarantine record must be an object.")
  val entries = map["entries"] as? List<*>
    ?: quarantineSchemaError("Feature-task-runtime quarantine record must carry an 'entries' array.")
  return entries.map { entry ->
    @Suppress("UNCHECKED_CAST")
    FeatureTaskRuntimeQuarantineEntry.fromArtifactMap(
      entry as? Map<String, Any?>
        ?: quarantineSchemaError("Feature-task-runtime quarantine entry must be an object."),
    )
  }
}
