package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.appendBoundedHistoryBySequence

/**
 * Effect-free durable model for one implementation attempt: the bounded receipt a mutating-phase
 * launch produced plus the attempt identity the runtime minted for it. There is no clock and no
 * randomness here — the application layer mints `sequenceNumber` and `recordedAt` and passes them in.
 *
 * The projection is bounded and derived by construction: there is no field that can hold a raw
 * prompt, a raw phase response, or a rejected-output body.
 */
data class FeatureTaskRuntimeImplementationAttempt(
  val sequenceNumber: Int,
  val phaseId: String,
  val attemptNumber: Int,
  val agentId: String,
  val status: FeatureTaskRuntimeImplementationAttemptStatus,
  val recordedAt: String,
  val completedTaskIds: List<String> = emptyList(),
  val changedPaths: List<String> = emptyList(),
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val deviations: List<FeatureTaskRuntimeReceiptDeviation> = emptyList(),
  val unresolvedItems: List<String> = emptyList(),
  val reconciliationEvidence: FeatureTaskRuntimeReceiptReconciliation? = null,
  val repositoryCheckpoint: FeatureTaskRuntimeReceiptCheckpoint? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimeImplementationAttempt.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.phaseId must be non-blank." }
    require(attemptNumber >= 1) {
      "FeatureTaskRuntimeImplementationAttempt.attemptNumber must be >= 1, was $attemptNumber."
    }
    require(agentId.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.agentId must be non-blank." }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.recordedAt must be non-blank." }
    require(completedTaskIds.distinct().size == completedTaskIds.size) {
      "FeatureTaskRuntimeImplementationAttempt.completedTaskIds must not repeat a task id."
    }
    require(completedTaskIds.all(String::isNotBlank)) {
      "FeatureTaskRuntimeImplementationAttempt.completedTaskIds must not contain blanks."
    }
    require(changedPaths.distinct().size == changedPaths.size) {
      "FeatureTaskRuntimeImplementationAttempt.changedPaths must not repeat a path."
    }
    require(unresolvedItems.all(String::isNotBlank)) {
      "FeatureTaskRuntimeImplementationAttempt.unresolvedItems must not contain blanks."
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimeImplementationAttempt.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  /**
   * Whether this attempt still owes work. Retention prefers to prune attempts that owe nothing, so a
   * bounded store never silently discards the obligation set the continuation projection is derived
   * from while a free slot remains.
   */
  val carriesOpenObligation: Boolean
    get() = unresolvedItems.isNotEmpty() ||
      status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE ||
      reconciliationEvidence?.reconciled == false

  @OpenBoundaryMap("Feature-task-runtime implementation-attempt entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "sequence_number" to sequenceNumber,
    "phase_id" to phaseId,
    "attempt_number" to attemptNumber,
    "agent_id" to agentId,
    "status" to status.wireValue,
    "recorded_at" to recordedAt,
    "completed_task_ids" to completedTaskIds,
    "changed_paths" to changedPaths,
  ).apply {
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
    failureDisposition?.let { put("failure_disposition", it.wireValue) }
    if (deviations.isNotEmpty()) put("deviations", deviations.map { it.toArtifactMap() })
    if (unresolvedItems.isNotEmpty()) put("unresolved_items", unresolvedItems)
    reconciliationEvidence?.let { put("reconciliation_evidence", it.toArtifactMap()) }
    repositoryCheckpoint?.let { put("repository_checkpoint", it.toArtifactMap()) }
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed field and never best-effort fills a default. */
    @OpenBoundaryMap("Feature-task-runtime implementation-attempt decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeImplementationAttempt {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        implementationAttemptError(
          "Feature-task-runtime implementation-attempt entry carries unsupported fields; the store is " +
            "quarantined and regenerated rather than reinterpreted.",
        )
      }
      return try {
        FeatureTaskRuntimeImplementationAttempt(
          sequenceNumber = raw.requireIntField("sequence_number"),
          phaseId = raw.requireStringField("phase_id"),
          attemptNumber = raw.requireIntField("attempt_number"),
          agentId = raw.requireStringField("agent_id"),
          status = FeatureTaskRuntimeImplementationAttemptStatus.fromWireValue(raw.requireStringField("status")),
          recordedAt = raw.requireStringField("recorded_at"),
          completedTaskIds = raw.optionalStringListField("completed_task_ids"),
          changedPaths = raw.optionalStringListField("changed_paths"),
          loopId = raw.optionalStringField("loop_id"),
          edgeIteration = raw.optionalAttemptIntField("edge_iteration"),
          failureDisposition = raw.optionalStringField("failure_disposition")?.let { value ->
            FeatureTaskRuntimeFailureDisposition.fromWireValue(value)
              ?: implementationAttemptError(
                "Feature-task-runtime implementation-attempt 'failure_disposition' has unsupported value.",
              )
          },
          deviations = (raw["deviations"] as? List<*>).orEmpty().map { value ->
            FeatureTaskRuntimeReceiptDeviation.fromArtifactMap(
              JsonSupport.anyToStringAnyMap(value)
                ?: implementationAttemptError(
                  "Feature-task-runtime implementation-attempt 'deviations' must contain only objects.",
                ),
            )
          },
          unresolvedItems = raw.optionalStringListField("unresolved_items"),
          reconciliationEvidence = raw["reconciliation_evidence"]?.let { value ->
            FeatureTaskRuntimeReceiptReconciliation.fromArtifactMap(
              JsonSupport.anyToStringAnyMap(value)
                ?: implementationAttemptError(
                  "Feature-task-runtime implementation-attempt 'reconciliation_evidence' must be an object.",
                ),
            )
          },
          repositoryCheckpoint = raw["repository_checkpoint"]?.let { value ->
            FeatureTaskRuntimeReceiptCheckpoint.fromArtifactMap(
              JsonSupport.anyToStringAnyMap(value)
                ?: implementationAttemptError(
                  "Feature-task-runtime implementation-attempt 'repository_checkpoint' must be an object.",
                ),
            )
          },
        )
      } catch (error: IllegalArgumentException) {
        implementationAttemptError(
          "Feature-task-runtime implementation-attempt entry violates its invariants: ${error.message.orEmpty()}",
        )
      }
    }

    private val ALLOWED_FIELDS = setOf(
      "sequence_number", "phase_id", "attempt_number", "agent_id", "status", "recorded_at",
      "completed_task_ids", "changed_paths", "loop_id", "edge_iteration", "failure_disposition",
      "deviations", "unresolved_items", "reconciliation_evidence", "repository_checkpoint",
    )
  }
}

/**
 * The envelope status the receipt was carried on, plus [INCOMPLETE] for a receipt that was
 * schema-valid but did not close every planned obligation. [INCOMPLETE] is deliberately not a
 * schema-invalidity marker: it records semantic incompleteness so the continuation loop can be told
 * apart from structural repair on both status surfaces and telemetry.
 */
enum class FeatureTaskRuntimeImplementationAttemptStatus(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  FAILED("failed"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimeImplementationAttemptStatus =
      entries.firstOrNull { it.wireValue == value }
        ?: implementationAttemptError(
          "Feature-task-runtime implementation-attempt 'status' has unsupported value '$value'.",
        )
  }
}

data class FeatureTaskRuntimeReceiptDeviation(val ref: String, val note: String) {
  init {
    require(ref.isNotBlank()) { "FeatureTaskRuntimeReceiptDeviation.ref must be non-blank." }
    require(note.isNotBlank()) { "FeatureTaskRuntimeReceiptDeviation.note must be non-blank." }
  }

  @OpenBoundaryMap("Feature-task-runtime receipt deviation at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf("ref" to ref, "note" to note)

  companion object {
    @OpenBoundaryMap("Feature-task-runtime receipt deviation decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeReceiptDeviation =
      FeatureTaskRuntimeReceiptDeviation(
        ref = raw.requireStringField("ref"),
        note = raw.requireStringField("note"),
      )
  }
}

data class FeatureTaskRuntimeReceiptReconciliation(val reconciled: Boolean, val evidence: String) {
  init {
    require(evidence.isNotBlank()) { "FeatureTaskRuntimeReceiptReconciliation.evidence must be non-blank." }
  }

  @OpenBoundaryMap("Feature-task-runtime receipt reconciliation evidence at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf("reconciled" to reconciled, "evidence" to evidence)

  companion object {
    @OpenBoundaryMap("Feature-task-runtime receipt reconciliation decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeReceiptReconciliation =
      FeatureTaskRuntimeReceiptReconciliation(
        reconciled = raw["reconciled"] as? Boolean
          ?: implementationAttemptError(
            "Feature-task-runtime receipt 'reconciliation_evidence.reconciled' must decode to a boolean.",
          ),
        evidence = raw.requireStringField("evidence"),
      )
  }
}

data class FeatureTaskRuntimeReceiptCheckpoint(
  val fingerprint: String,
  val baseRef: String? = null,
  val headRef: String? = null,
) {
  init {
    require(fingerprint.isNotBlank()) { "FeatureTaskRuntimeReceiptCheckpoint.fingerprint must be non-blank." }
  }

  @OpenBoundaryMap("Feature-task-runtime receipt repository checkpoint at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>("fingerprint" to fingerprint).apply {
    baseRef?.let { put("base_ref", it) }
    headRef?.let { put("head_ref", it) }
  }

  companion object {
    @OpenBoundaryMap("Feature-task-runtime receipt checkpoint decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeReceiptCheckpoint =
      FeatureTaskRuntimeReceiptCheckpoint(
        fingerprint = raw.requireStringField("fingerprint"),
        baseRef = raw.optionalStringField("base_ref"),
        headRef = raw.optionalStringField("head_ref"),
      )
  }
}

/** The list-envelope wire form; carries the pinned contract version alongside the ordered entries. */
@OpenBoundaryMap("Feature-task-runtime implementation-attempt record at the durable workflow-artifact seam")
fun featureTaskRuntimeImplementationAttemptRecordToWire(
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
): Map<String, Any?> = linkedMapOf(
  "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
  "attempts" to attempts.map { it.toArtifactMap() },
)

/**
 * Strict decode of the durable implementation-attempt store. An absent key is the caller's concern
 * (it decodes to an empty history there); a present-but-malformed value loud-fails here so the
 * runtime quarantines and regenerates rather than reading an understated obligation set.
 */
@OpenBoundaryMap("Feature-task-runtime implementation-attempt decode from the durable workflow-artifact map")
fun featureTaskRuntimeImplementationAttemptsFromWire(raw: Any?): List<FeatureTaskRuntimeImplementationAttempt> {
  val map = raw as? Map<*, *>
    ?: implementationAttemptError("Feature-task-runtime implementation-attempt record must be an object.")
  val version = map["contract_version"]
  if (version != FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION) {
    implementationAttemptError(
      "Feature-task-runtime implementation-attempt record uses unsupported contract version '$version'; " +
        "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
    )
  }
  val attempts = map["attempts"] as? List<*>
    ?: implementationAttemptError(
      "Feature-task-runtime implementation-attempt record must carry an 'attempts' array.",
    )
  return attempts.map { entry ->
    FeatureTaskRuntimeImplementationAttempt.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(entry)
        ?: implementationAttemptError("Feature-task-runtime implementation-attempt entry must be an object."),
    )
  }
}

/**
 * Appends one attempt to the durable history and prunes to
 * [FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_LIMIT]. Ordering by `sequence_number` and
 * oldest-first pruning come from the shared [appendBoundedHistoryBySequence] helper; the only thing
 * added here is that an attempt still carrying an open obligation is passed over for pruning while a
 * closed attempt is available to drop instead. Dropping an open obligation to retain a closed one
 * would let the continuation projection understate what is still owed.
 */
fun featureTaskRuntimeAppendImplementationAttempt(
  existing: List<FeatureTaskRuntimeImplementationAttempt>,
  entry: FeatureTaskRuntimeImplementationAttempt,
  retentionLimit: Int = FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_LIMIT,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val ordered = appendBoundedHistoryBySequence(
    existing = existing.map { it.toArtifactMap() },
    entry = entry.toArtifactMap(),
    retentionLimit = Int.MAX_VALUE,
  ).map(FeatureTaskRuntimeImplementationAttempt::fromArtifactMap)
  if (ordered.size <= retentionLimit) return ordered
  val overflow = ordered.size - retentionLimit
  val droppableIndices = ordered.indices.filterNot { ordered[it].carriesOpenObligation }.take(overflow)
  // Every retained attempt still owes work: retention is a hard bound, so fall back to plain
  // oldest-first rather than growing the store past its declared limit.
  val dropped = if (droppableIndices.size == overflow) droppableIndices.toSet() else (0 until overflow).toSet()
  return ordered.filterIndexed { index, _ -> index !in dropped }
}

private fun implementationAttemptError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

private fun Map<String, Any?>.optionalAttemptIntField(key: String): Int? {
  if (!containsKey(key) || this[key] == null) return null
  return (this[key] as? Number)?.toInt()
    ?: implementationAttemptError(
      "Feature-task-runtime artifact field '$key' must decode to an integer when present.",
    )
}
