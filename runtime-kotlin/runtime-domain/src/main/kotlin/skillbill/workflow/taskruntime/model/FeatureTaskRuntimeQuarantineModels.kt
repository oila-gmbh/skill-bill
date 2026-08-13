package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
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
const val FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION: String = "0.3"

/** Typed classes of launch-seam rejection that trigger quarantine; mirror the schema enum. */
const val QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION: String = "planning_projection_schema"
const val QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE: String = "handoff_envelope_schema"

private val QUARANTINE_REJECTION_CLASSES: Set<String> = setOf(
  QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
  QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE,
)

private val QUARANTINE_ENVELOPE_FIELDS: Set<String> = setOf("contract_version", "entries")

/**
 * One quarantined durable record. Names the producing phase (which will be regenerated), the
 * consuming phase whose launch seam rejected it, the rejected producing iteration, the typed
 * rejection class and bounded detail, the per-producer regeneration attempt at quarantine, the
 * consuming-phase iteration at quarantine, and either a private diagnostic identity or a mark that
 * the diagnostic write degraded. Identity and the degraded flag are mutually exclusive.
 */
data class FeatureTaskRuntimeQuarantineEntry(
  val producingPhaseId: String,
  val consumingPhaseId: String,
  val producingIteration: Int,
  val rejectionClass: String,
  val rejectionDetail: String,
  val regenerationAttempt: Int,
  val quarantinedAtIteration: Int,
  val diagnosticIdentity: String?,
  val rejectedRecordByteSize: Long,
  val rejectedRecordSha256: String,
  val diagnosticDegraded: Boolean = false,
) {
  init {
    require(producingPhaseId.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.producingPhaseId must be non-blank." }
    require(consumingPhaseId.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.consumingPhaseId must be non-blank." }
    require(producingIteration >= 1) { "FeatureTaskRuntimeQuarantineEntry.producingIteration must be >= 1." }
    require(rejectionClass in QUARANTINE_REJECTION_CLASSES) {
      "FeatureTaskRuntimeQuarantineEntry.rejectionClass must be a declared class."
    }
    require(rejectionDetail.isNotBlank()) { "FeatureTaskRuntimeQuarantineEntry.rejectionDetail must be non-blank." }
    require(regenerationAttempt >= 1) { "FeatureTaskRuntimeQuarantineEntry.regenerationAttempt must be >= 1." }
    require(quarantinedAtIteration >= 1) { "FeatureTaskRuntimeQuarantineEntry.quarantinedAtIteration must be >= 1." }
    require(diagnosticDegraded xor (diagnosticIdentity != null)) {
      "FeatureTaskRuntimeQuarantineEntry must carry diagnosticIdentity xor diagnosticDegraded=true."
    }
    require(diagnosticIdentity == null || diagnosticIdentity.isNotBlank()) {
      "FeatureTaskRuntimeQuarantineEntry.diagnosticIdentity must be non-blank when present."
    }
    require(rejectedRecordByteSize >= 0) { "FeatureTaskRuntimeQuarantineEntry.rejectedRecordByteSize must be >= 0." }
    require(Regex("[0-9a-f]{64}").matches(rejectedRecordSha256)) {
      "FeatureTaskRuntimeQuarantineEntry.rejectedRecordSha256 must be a lowercase SHA-256 digest."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime quarantine entry artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>(
      "producing_phase_id" to producingPhaseId,
      "consuming_phase_id" to consumingPhaseId,
      "producing_iteration" to producingIteration,
      "rejection_class" to rejectionClass,
      "rejection_detail" to rejectionDetail,
      "regeneration_attempt" to regenerationAttempt,
      "quarantined_at_iteration" to quarantinedAtIteration,
    )
    if (diagnosticDegraded) {
      map["diagnostic_degraded"] = true
    } else {
      map["diagnostic_identity"] = requireNotNull(diagnosticIdentity)
    }
    map["rejected_record_byte_size"] = rejectedRecordByteSize
    map["rejected_record_sha256"] = rejectedRecordSha256
    return map
  }

  /** A stable identifier for this quarantined record, used in cap-exhaustion block reasons. */
  fun recordIdentifier(): String = "$producingPhaseId#$producingIteration"

  companion object {
    private val ALLOWED_FIELDS: Set<String> = setOf(
      "producing_phase_id",
      "consuming_phase_id",
      "producing_iteration",
      "rejection_class",
      "rejection_detail",
      "regeneration_attempt",
      "quarantined_at_iteration",
      "diagnostic_identity",
      "diagnostic_degraded",
      "rejected_record_byte_size",
      "rejected_record_sha256",
    )

    /** Strict decode; loud-fails on a missing, malformed, or undeclared field. */
    @OpenBoundaryMap("Feature-task-runtime quarantine entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeQuarantineEntry {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        quarantineSchemaError(
          "Feature-task-runtime quarantine entry carries unsupported fields ${unexpected.sorted()}; " +
            "the store is left untouched rather than rewritten without that evidence.",
        )
      }
      return try {
        FeatureTaskRuntimeQuarantineEntry(
          producingPhaseId = raw.requireStringField("producing_phase_id"),
          consumingPhaseId = raw.requireStringField("consuming_phase_id"),
          producingIteration = raw.requireIntField("producing_iteration"),
          rejectionClass = raw.requireStringField("rejection_class"),
          rejectionDetail = raw.requireStringField("rejection_detail"),
          regenerationAttempt = raw.requireIntField("regeneration_attempt"),
          quarantinedAtIteration = raw.requireIntField("quarantined_at_iteration"),
          diagnosticIdentity = raw.optionalStringField("diagnostic_identity"),
          rejectedRecordByteSize = raw.requireIntField("rejected_record_byte_size").toLong(),
          rejectedRecordSha256 = raw.requireStringField("rejected_record_sha256"),
          diagnosticDegraded = raw.requireDiagnosticDegradedFlag(),
        )
      } catch (error: IllegalArgumentException) {
        quarantineSchemaError("Feature-task-runtime quarantine entry is malformed: ${error.message}")
      }
    }

    private fun Map<String, Any?>.requireDiagnosticDegradedFlag(): Boolean =
      when (optionalBooleanField("diagnostic_degraded")) {
        null -> false
        true -> true
        false -> quarantineSchemaError(
          "Feature-task-runtime quarantine entry 'diagnostic_degraded' must be true when present.",
        )
      }
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
  val map = JsonSupport.anyToStringAnyMap(raw)
    ?: quarantineSchemaError("Feature-task-runtime quarantine record must be an object.")
  val unexpected = map.keys - QUARANTINE_ENVELOPE_FIELDS
  if (unexpected.isNotEmpty()) {
    quarantineSchemaError(
      "Feature-task-runtime quarantine record carries unsupported fields ${unexpected.sorted()}; " +
        "the store is left untouched rather than rewritten without that evidence.",
    )
  }
  val version = map["contract_version"] as? String
  if (version != FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION) {
    quarantineSchemaError(
      "Feature-task-runtime quarantine record uses unsupported contract version " +
        "'${version.orEmpty()}'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
    )
  }
  val entries = map["entries"] as? List<*>
    ?: quarantineSchemaError("Feature-task-runtime quarantine record must carry an 'entries' array.")
  return entries.map { entry ->
    FeatureTaskRuntimeQuarantineEntry.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(entry)
        ?: quarantineSchemaError("Feature-task-runtime quarantine entry must be an object."),
    )
  }
}
