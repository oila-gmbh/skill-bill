package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap

const val GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY: String = "goal_session_accounting"
const val GOAL_SESSION_ACCOUNTING_LIMIT: Int = 50
const val GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY: String = "goal_attempt_ledger"
const val GOAL_ATTEMPT_LEDGER_LIMIT: Int = 200

/**
 * SKILL-64 Subtask 3 (AC6, AC7): best-effort child-session token/session
 * accounting. Effect-free; timestamps minted in adapters. When the provider
 * log is missing or unparsable, [available] is false and [unavailableReason]
 * explains why, and the record MUST NOT fail an otherwise valid run.
 */
data class GoalSessionAccounting(
  val subtaskId: Int,
  val phase: String,
  val available: Boolean,
  val sequenceNumber: Int,
  val timestamp: String,
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val model: String? = null,
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningOutputTokens: Long? = null,
  val finalStatus: String? = null,
  val unavailableReason: String? = null,
) {
  init {
    require(subtaskId > 0) { "GoalSessionAccounting.subtaskId must be positive." }
    require(phase.isNotBlank()) { "GoalSessionAccounting.phase is required." }
    require(sequenceNumber >= 0) { "GoalSessionAccounting.sequenceNumber must be non-negative." }
    require(timestamp.isNotBlank()) { "GoalSessionAccounting.timestamp is required." }
    if (!available) {
      require(!unavailableReason.isNullOrBlank()) {
        "GoalSessionAccounting.unavailableReason is required when accounting is unavailable."
      }
    }
  }

  @OpenBoundaryMap("Goal session accounting artifact map at durable workflow-artifact/schema seams")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "subtask_id" to subtaskId,
    "phase" to phase,
    "available" to available,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
  ).apply {
    childSessionPath?.let { put("child_session_path", it) }
    childSessionId?.let { put("child_session_id", it) }
    model?.let { put("model", it) }
    inputTokens?.let { put("input_tokens", it) }
    cachedInputTokens?.let { put("cached_input_tokens", it) }
    outputTokens?.let { put("output_tokens", it) }
    reasoningOutputTokens?.let { put("reasoning_output_tokens", it) }
    finalStatus?.let { put("final_status", it) }
    unavailableReason?.let { put("unavailable_reason", it) }
  }
}

/**
 * SKILL-64 Subtask 3 (AC6, AC7): best-effort, effect-free child-session
 * accounting parser. Accepts already-extracted, provider-neutral fields (the
 * adapter does any optional reads) and never throws: when no usable token data
 * is present it returns an accounting-unavailable record rather than failing.
 * Loud only at its own contract seam (the [GoalSessionAccounting] invariants),
 * soft elsewhere.
 */
object GoalSessionAccountingParser {
  fun parse(
    subtaskId: Int,
    phase: String,
    sequenceNumber: Int,
    timestamp: String,
    fields: GoalSessionAccountingFields,
  ): GoalSessionAccounting {
    val hasAnyTokenData = listOf(
      fields.inputTokens,
      fields.cachedInputTokens,
      fields.outputTokens,
      fields.reasoningOutputTokens,
    ).any { it != null }
    return if (!hasAnyTokenData && fields.childSessionId.isNullOrBlank() && fields.childSessionPath.isNullOrBlank()) {
      GoalSessionAccounting(
        subtaskId = subtaskId,
        phase = phase,
        available = false,
        sequenceNumber = sequenceNumber,
        timestamp = timestamp,
        unavailableReason = fields.unavailableReason?.takeIf(String::isNotBlank)
          ?: "No provider session accounting was available for this child run.",
      )
    } else {
      GoalSessionAccounting(
        subtaskId = subtaskId,
        phase = phase,
        available = true,
        sequenceNumber = sequenceNumber,
        timestamp = timestamp,
        childSessionPath = fields.childSessionPath?.takeIf(String::isNotBlank),
        childSessionId = fields.childSessionId?.takeIf(String::isNotBlank),
        model = fields.model?.takeIf(String::isNotBlank),
        inputTokens = fields.inputTokens,
        cachedInputTokens = fields.cachedInputTokens,
        outputTokens = fields.outputTokens,
        reasoningOutputTokens = fields.reasoningOutputTokens,
        finalStatus = fields.finalStatus?.takeIf(String::isNotBlank),
      )
    }
  }
}

data class GoalSessionAccountingFields(
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val model: String? = null,
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningOutputTokens: Long? = null,
  val finalStatus: String? = null,
  val unavailableReason: String? = null,
)

data class GoalSessionAccountingHistory(
  val entries: List<GoalSessionAccounting> = emptyList(),
  val retentionLimit: Int = GOAL_SESSION_ACCOUNTING_LIMIT,
) {
  fun append(entry: GoalSessionAccounting): GoalSessionAccountingHistory =
    copy(entries = (entries + entry).sortedBy(GoalSessionAccounting::sequenceNumber).takeLast(retentionLimit))

  @OpenBoundaryMap("Goal session accounting history artifact list at durable workflow-artifact/schema seams")
  fun toArtifactList(): List<Map<String, Any?>> = entries.map(GoalSessionAccounting::toArtifactMap)
}

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
