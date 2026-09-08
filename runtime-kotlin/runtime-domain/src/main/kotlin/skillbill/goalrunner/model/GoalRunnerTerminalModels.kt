package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionSubtask

enum class GoalRunnerTerminalStatus {
  COMPLETE,
  FAILED,
  BLOCKED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,

  /**
   * A non-terminal child row that crash reconciliation transitioned to the resumable pending state
   * (killed child, expired lease, dead process). Not a failure: the goal parent reports the subtask
   * resumable so `skill-bill goal <key>` resume continues without manual lease or row clearing.
   */
  RECONCILABLE,

  /**
   * A non-terminal child waiting on the bounded operator decision after the reserved remediation pass
   * left an unresolved Blocker. Not a failure and not blocked: the persisted review state, baseline,
   * and consumed pass count survive, so resume continues from the recorded resumable step.
   */
  PAUSED,
}

enum class GoalRunnerStopReason {
  FAILED,
  BLOCKED,
  POLICY_BLOCKED,
  INTERRUPTED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,
  PULL_REQUEST_FAILED,
  DEPENDENCIES_BLOCKED,

  /** The child row was crash-reconciled to resumable; the goal halts but the subtask stays resumable. */
  RECONCILED_RESUMABLE,

  /**
   * The child paused after the reserved remediation pass left an unresolved Blocker. The goal halts
   * awaiting the bounded operator decision; the subtask stays resumable at its recorded step.
   */
  AWAITING_OPERATOR_DECISION,

  /** The parent reached a durable operator or stop-after-subtask pause boundary. */
  PAUSED,
  ;

  companion object {
    /** Stop reasons that leave the subtask resumable rather than stopped. */
    val RESUMABLE_STOP_REASONS = setOf(RECONCILED_RESUMABLE, AWAITING_OPERATOR_DECISION, PAUSED)
  }
}

data class GoalRunnerStoredOutcome(
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String? = null,
  val suppressPr: Boolean,
)

sealed interface GoalRunnerReconciledOutcome {
  data class Complete(
    val workflowId: String,
    val commitSha: String,
    val lastResumableStep: String,
  ) : GoalRunnerReconciledOutcome

  data class Stop(
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val workflowId: String?,
    val commitSha: String?,
    val lastResumableStep: String,
    val liveness: GoalRunnerLivenessSnapshot? = null,
  ) : GoalRunnerReconciledOutcome
}

data class GoalRunnerSubtaskDecision(
  val subtask: DecompositionSubtask,
  val action: GoalRunnerSubtaskAction,
)

enum class GoalRunnerSubtaskAction {
  START,
  RESUME,
}

sealed interface GoalRunnerSelection {
  data class Run(val decision: GoalRunnerSubtaskDecision) : GoalRunnerSelection
  data class Blocked(val subtask: DecompositionSubtask, val reason: String) : GoalRunnerSelection
  data object Done : GoalRunnerSelection
}

data class GoalRunnerStopReport(
  val issueKey: String,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
  val lastResumableStep: String,
)

sealed interface GoalRunnerRunReport {
  val issueKey: String
  val attemptedSubtasks: List<Int>

  data class Completed(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val pullRequestUrl: String?,
    val pullRequestStatus: String,
    val subtasksCompleted: Int,
    val subtasksPending: Int,
    val subtasksBlocked: Int,
    val unaddressedFindingCount: Int? = 0,
    val unaddressedSeverityBreakdown: Map<String, Int> = emptyMap(),
    val featureName: String? = null,
  ) : GoalRunnerRunReport

  data class Stopped(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val stop: GoalRunnerStopReport,
  ) : GoalRunnerRunReport
}
