package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError

enum class FeatureTaskRuntimePhaseExecutionOrigin(val wireValue: String) {
  AGENT_EXECUTED("agent-executed"),
  GOAL_PLANNING_HYDRATED("goal-planning-hydrated"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimePhaseExecutionOrigin =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime artifact field 'execution_origin' has unsupported value '$value'.",
        )
  }
}

enum class FeatureTaskRuntimeFailureDisposition(val wireValue: String, val retryOnResume: Boolean) {
  RETRYABLE("retryable", true),
  NON_RETRYABLE_POLICY_CONFLICT("non_retryable_policy_conflict", false),
  NEEDS_USER_ACTION("needs_user_action", false),
  PROCESS_FAILURE("process_failure", true),
  INVALID_OUTPUT("invalid_output", true),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimeFailureDisposition? =
      entries.firstOrNull { it.wireValue == value }
  }
}

/** Actions for the append-only phase attempt/event ledger. */
enum class FeatureTaskRuntimePhaseLedgerAction(val wireValue: String) {
  START("start"),
  RESUME("resume"),
  RETRY("retry"),
  FIX_LOOP_ITERATION("fix_loop_iteration"),
  LOOP_EDGE("loop_edge"),
  BLOCKED("blocked"),
  PAUSED("paused"),
  COMPLETE("complete"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseLedgerAction = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidWorkflowStateSchemaError(
        "Unknown feature-task-runtime phase ledger action '$value'. " +
          "Allowed: ${entries.joinToString { it.wireValue }}.",
      )
  }
}

/**
 * One append-only phase ledger entry with a monotonic [sequenceNumber] and an
 * application-minted [timestamp].
 */
data class FeatureTaskRuntimePhaseLedgerEntry(
  val action: FeatureTaskRuntimePhaseLedgerAction,
  val sequenceNumber: Int,
  val timestamp: String,
  val phaseId: String,
  val attemptCount: Int,
  val resolvedAgentId: String? = null,
  val executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin =
    FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
  val fixLoopIteration: Int? = null,
  val blockedReason: String? = null,
  /** Authoritative per-edge trail for a backward-edge re-entry, distinct from [attemptCount]. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimePhaseLedgerEntry.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(timestamp.isNotBlank()) { "FeatureTaskRuntimePhaseLedgerEntry.timestamp must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseLedgerEntry.phaseId must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseLedgerEntry.attemptCount must be >= 1, was $attemptCount."
    }
    fixLoopIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.fixLoopIteration must be >= 1 when present, was $iteration."
      }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime phase ledger entry artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "action" to action.wireValue,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
    "phase_id" to phaseId,
    "attempt_count" to attemptCount,
  ).apply {
    resolvedAgentId?.let { put("resolved_agent_id", it) }
    put("execution_origin", executionOrigin.wireValue)
    fixLoopIteration?.let { put("fix_loop_iteration", it) }
    blockedReason?.let { put("blocked_reason", it) }
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
  }

  companion object {
    /** Strict decode; loud-fails on any missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime phase ledger entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLedgerEntry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.fromWire(raw.requireStringField("action")),
        sequenceNumber = raw.requireIntField("sequence_number"),
        timestamp = raw.requireStringField("timestamp"),
        phaseId = requireKnownFeatureTaskRuntimePhaseId(raw.requireStringField("phase_id"), "phase_id"),
        attemptCount = raw.requireIntField("attempt_count"),
        resolvedAgentId = raw.optionalStringField("resolved_agent_id"),
        executionOrigin = raw.optionalStringField("execution_origin")?.let(
          FeatureTaskRuntimePhaseExecutionOrigin::fromWireValue,
        ) ?: FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
        fixLoopIteration = raw.optionalIntField("fix_loop_iteration"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        loopId = raw.optionalStringField("loop_id"),
        edgeIteration = raw.optionalIntField("edge_iteration"),
      )
  }
}
