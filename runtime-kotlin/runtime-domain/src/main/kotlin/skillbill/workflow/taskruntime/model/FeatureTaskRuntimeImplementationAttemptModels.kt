package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence

data class FeatureTaskRuntimeImplementationAttempt(
  val sequenceNumber: Int,
  val phaseId: String,
  val attemptNumber: Int,
  val agentId: String,
  val status: FeatureTaskRuntimeImplementationAttemptStatus,
  val recordedAt: String,
  val value: String,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val prompt: String? = null,
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
    require(value.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.value must be non-blank." }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimeImplementationAttempt.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  val carriesOpenObligation: Boolean
    get() = status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE

  @OpenBoundaryMap("Feature-task-runtime implementation-attempt entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "sequence_number" to sequenceNumber,
    "phase_id" to phaseId,
    "attempt_number" to attemptNumber,
    "agent_id" to agentId,
    "status" to status.wireValue,
    "recorded_at" to recordedAt,
    "value" to value,
  ).apply {
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
    failureDisposition?.let { put("failure_disposition", it.wireValue) }
    prompt?.let { put("prompt", it) }
  }

  companion object {
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
          value = raw.requireStringField("value"),
          loopId = raw.optionalStringField("loop_id"),
          edgeIteration = raw.optionalAttemptIntField("edge_iteration"),
          failureDisposition = raw.optionalStringField("failure_disposition")?.let { value ->
            FeatureTaskRuntimeFailureDisposition.fromWireValue(value)
              ?: implementationAttemptError(
                "Feature-task-runtime implementation-attempt 'failure_disposition' has unsupported value.",
              )
          },
          prompt = raw.optionalStringField("prompt"),
        )
      } catch (error: IllegalArgumentException) {
        implementationAttemptError(
          "Feature-task-runtime implementation-attempt entry violates its invariants: ${error.message.orEmpty()}",
        )
      }
    }

    private val ALLOWED_FIELDS = setOf(
      "sequence_number", "phase_id", "attempt_number", "agent_id", "status", "recorded_at",
      "value", "loop_id", "edge_iteration", "failure_disposition", "prompt",
    )
  }
}

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

@OpenBoundaryMap("Feature-task-runtime implementation-attempt record at the durable workflow-artifact seam")
fun featureTaskRuntimeImplementationAttemptRecordToWire(
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
): Map<String, Any?> = linkedMapOf(
  "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
  "attempts" to attempts.map { it.toArtifactMap() },
)

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
