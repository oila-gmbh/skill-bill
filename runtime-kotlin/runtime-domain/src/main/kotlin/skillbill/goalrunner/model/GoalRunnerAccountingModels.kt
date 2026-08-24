package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap

const val GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY: String = "goal_attempt_ledger"
const val GOAL_ATTEMPT_LEDGER_LIMIT: Int = 200

/**
 * SKILL-64 Subtask 3 (AC10, AC11): append-only attempt/event ledger entry. One
 * entry is appended per child activation, resume/retry, terminal done check,
 * policy block, timeout, interruption, and final reconciled outcome. Effect-
 * free; timestamps minted in adapters. The sequence space is distinct from the
 * goal_event and goal_progress sequence spaces.
 */
enum class GoalAttemptLedgerAction(val wireValue: String) {
  CHILD_ACTIVATION("child_activation"),
  RESUME("resume"),
  RETRY("retry"),
  TERMINAL_DONE_CHECK("terminal_done_check"),
  POLICY_BLOCK("policy_block"),
  TIMEOUT("timeout"),
  INTERRUPTION("interruption"),
  FINAL_RECONCILED_OUTCOME("final_reconciled_outcome"),
  DIAGNOSTIC_INSPECTION("diagnostic_inspection"),

  /** A backward edge was taken for a subtask; records the loop id and the running cumulative count. */
  BACKWARD_EDGE_ENTRY("backward_edge_entry"),
  ;

  companion object {
    fun fromWire(value: String): GoalAttemptLedgerAction = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException("Unknown goal attempt ledger action '$value'.")
  }
}

data class GoalAttemptLedgerEntry(
  val action: GoalAttemptLedgerAction,
  val sequenceNumber: Int,
  val timestamp: String,
  val issueKey: String? = null,
  val subtaskId: Int? = null,
  val previousWorkflowId: String? = null,
  val previousStatus: String? = null,
  val previousStep: String? = null,
  val blockedReason: String? = null,
  val latestLiveness: String? = null,
  val launchOutcome: String? = null,
  val timedOut: Boolean? = null,
  val interrupted: Boolean? = null,
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val finalReconciledResult: String? = null,
  val stopReason: String? = null,
  val diagnosticClass: String? = null,
  val currentStep: String? = null,
  val exitStatus: Int? = null,
  val recoverableJsonPresent: Boolean? = null,
  val nextSafeAction: String? = null,
  val loopId: String? = null,
  val cumulativeLoopCount: Int? = null,
  val attemptDurationMillis: Long? = null,
  val causingLoopEntry: String? = null,
  val reAttemptCause: String? = null,
  val findingsInScope: Int? = null,
) {
  init {
    require(sequenceNumber >= 0) { "GoalAttemptLedgerEntry.sequenceNumber must be non-negative." }
    require(timestamp.isNotBlank()) { "GoalAttemptLedgerEntry.timestamp is required." }
  }

  @OpenBoundaryMap("Goal attempt ledger entry artifact map at durable workflow-artifact/schema seams")
  fun toArtifactMap(): Map<String, Any?> {
    val optional = linkedMapOf<String, Any?>(
      "issue_key" to issueKey,
      "subtask_id" to subtaskId,
      "previous_workflow_id" to previousWorkflowId,
      "previous_status" to previousStatus,
      "previous_step" to previousStep,
      "blocked_reason" to blockedReason,
      "latest_liveness" to latestLiveness,
      "launch_outcome" to launchOutcome,
      "timed_out" to timedOut,
      "interrupted" to interrupted,
      "child_session_path" to childSessionPath,
      "child_session_id" to childSessionId,
      "final_reconciled_result" to finalReconciledResult,
      "stop_reason" to stopReason,
      "diagnostic_class" to diagnosticClass,
      "current_step" to currentStep,
      "exit_status" to exitStatus,
      "recoverable_json_present" to recoverableJsonPresent,
      "next_safe_action" to nextSafeAction,
      "loop_id" to loopId,
      "cumulative_loop_count" to cumulativeLoopCount,
      "attempt_duration_millis" to attemptDurationMillis,
      "causing_loop_entry" to causingLoopEntry,
      "re_attempt_cause" to reAttemptCause,
      "findings_in_scope" to findingsInScope,
    )
    return linkedMapOf<String, Any?>(
      "action" to action.wireValue,
      "sequence_number" to sequenceNumber,
      "timestamp" to timestamp,
    ).apply { putAll(optional.filterValues { it != null }) }
  }
}

data class GoalAttemptLedger(
  val entries: List<GoalAttemptLedgerEntry> = emptyList(),
  val retentionLimit: Int = GOAL_ATTEMPT_LEDGER_LIMIT,
) {
  fun append(entry: GoalAttemptLedgerEntry): GoalAttemptLedger =
    copy(entries = (entries + entry).sortedBy(GoalAttemptLedgerEntry::sequenceNumber).takeLast(retentionLimit))

  @OpenBoundaryMap("Goal attempt ledger artifact list at durable workflow-artifact/schema seams")
  fun toArtifactList(): List<Map<String, Any?>> = entries.map(GoalAttemptLedgerEntry::toArtifactMap)
}
