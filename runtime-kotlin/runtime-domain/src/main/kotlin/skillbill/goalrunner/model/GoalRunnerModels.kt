package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks

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

data class GoalRunnerLaunchFacts(
  val timedOut: Boolean = false,
  val interrupted: Boolean = false,
  val spawnFailed: Boolean = false,
  val exitStatus: Int? = null,
  val liveness: GoalRunnerLivenessSnapshot? = null,
  val stderrExcerpt: String? = null,
) {
  companion object {
    const val STDERR_EXCERPT_MAX_CHARS: Int = 3_000

    /**
     * Diagnostic class emitted when the supervisor killed a child whose last known liveness state
     * was [GoalRunnerLivenessState.WORKING] or [GoalRunnerLivenessState.PROGRESSING]. A kill at
     * this state is unexpected and warrants a separate diagnostic so telemetry can surface it
     * distinct from ordinary idle-timeout or unresponsive kills.
     */
    const val DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL = "supervisor_killed_confirmed_alive"
  }
}

/**
 * SKILL-64 Subtask 3 (AC23): documented, testable liveness taxonomy derived
 * ONLY from declared facts plus process liveness. Source-file mtimes, stdout
 * chatter, and token movement are non-authoritative hints and never determine
 * this state.
 *
 * - [WORKING]: an explicitly long declared operation is active and its process
 *   is alive. Disarms the idle timeout (AC22).
 * - [PROGRESSING]: a durable workflow event advanced within the interval.
 *   Disarms the idle timeout for the current interval.
 * - [IDLE]: no active explicitly long operation and no durable advance within
 *   the idle window. The ONLY state that arms the idle timeout.
 * - [UNRESPONSIVE]: the child/process is gone or a declared operation overran
 *   its deadline (or the wall-clock cap elapsed). A deterministic block, not an
 *   inference; does not arm the idle timeout because the decision is terminal.
 */
enum class GoalRunnerLivenessState(val wireValue: String) {
  WORKING("working"),
  PROGRESSING("progressing"),
  IDLE("idle"),
  UNRESPONSIVE("unresponsive"),
  ;

  /** Idle is the only state that arms the progress-idle timeout (AC22, AC23). */
  val armsIdleTimeout: Boolean
    get() = this == IDLE
}

/**
 * Declared facts the classifier reads. All fields are pure inputs computed by
 * the adapter from declared progress events plus process liveness; the
 * classifier itself performs no effects.
 */
data class GoalRunnerLivenessInputs(
  val processAlive: Boolean,
  val operationActive: Boolean,
  val operationExpectedLong: Boolean,
  val durableAdvanceWithinInterval: Boolean,
  val operationDeadlineOverrun: Boolean,
  val wallClockCapExceeded: Boolean,
)

data class GoalRunnerLivenessDecision(
  val state: GoalRunnerLivenessState,
  val armIdleTimeout: Boolean,
) {
  val disarmIdleTimeout: Boolean get() = !armIdleTimeout
}

/**
 * Pure classifier mapping declared facts to a [GoalRunnerLivenessState] and an
 * arm/disarm idle-timeout decision. Ordering encodes the documented semantics:
 * terminal unresponsive signals win first, then a live explicitly long
 * operation (working), then a durable advance (progressing), else idle.
 */
object GoalRunnerLivenessClassifier {
  fun classify(inputs: GoalRunnerLivenessInputs): GoalRunnerLivenessDecision {
    val state = when {
      !inputs.processAlive -> GoalRunnerLivenessState.UNRESPONSIVE
      inputs.operationActive && inputs.operationDeadlineOverrun -> GoalRunnerLivenessState.UNRESPONSIVE
      inputs.wallClockCapExceeded -> GoalRunnerLivenessState.UNRESPONSIVE
      inputs.operationActive && inputs.operationExpectedLong -> GoalRunnerLivenessState.WORKING
      inputs.durableAdvanceWithinInterval -> GoalRunnerLivenessState.PROGRESSING
      else -> GoalRunnerLivenessState.IDLE
    }
    return GoalRunnerLivenessDecision(state = state, armIdleTimeout = state.armsIdleTimeout)
  }
}

data class GoalRunnerLivenessSnapshot(
  val phase: String,
  val reason: String,
  val processState: String,
  val workflowId: String? = null,
  val workflowStep: String? = null,
  val lastDurableProgressAt: String? = null,
  val lastDurableProgressLabel: String? = null,
  val lastWorkflowSnapshotAt: String? = null,
  val lastFileActivityAt: String? = null,
  val lastFileActivityLabel: String? = null,
  val lastOutputAt: String? = null,
  val livenessState: GoalRunnerLivenessState? = null,
  /** True when the process was classified alive (WORKING or PROGRESSING) at the moment of capture. */
  val aliveAtKill: Boolean = false,
)

data class GoalRunnerSupervisionEvent(
  val phase: String,
  val reason: String,
  val continuationMode: String,
  val processState: String,
  val workflowId: String?,
  val stepId: String?,
  val lastDurableProgress: String?,
  val lastWorkflowSnapshotAt: String?,
  val lastFileActivityAt: String?,
  val lastOutputAt: String?,
)

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

data class GoalRunnerExecutionLease(
  val generation: Long,
  val ownerToken: String,
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
  val heartbeatAt: String,
  val expiresAt: String,
) {
  init {
    require(generation > 0) { "execution lease generation must be positive." }
    require(ownerToken.isNotBlank()) { "execution lease ownerToken must not be blank." }
    require(hostIdentity.isNotBlank()) { "execution lease hostIdentity must not be blank." }
    require(bootIdentity.isNotBlank()) { "execution lease bootIdentity must not be blank." }
    require(pid > 0) { "execution lease pid must be positive." }
    require(processBirthToken.isNotBlank()) { "execution lease processBirthToken must not be blank." }
    require(heartbeatAt.isNotBlank()) { "execution lease heartbeatAt must not be blank." }
    require(expiresAt.isNotBlank()) { "execution lease expiresAt must not be blank." }
  }
}

/** Durable parent-owned pause, stop-after, and execution state, separate from the checked-in manifest. */
data class GoalRunnerControlState(
  val stopAfterSubtaskId: Int? = null,
  val pauseRequested: Boolean = false,
  val pauseConsumed: Boolean = false,
  val paused: Boolean = false,
  val pauseReason: String? = null,
  val stopAfterConsumed: Boolean = false,
  val repositoryIdentity: String? = null,
  val executionLease: GoalRunnerExecutionLease? = null,
) {
  init {
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    require(!pauseConsumed || pauseRequested) {
      "pauseConsumed cannot be true when pauseRequested is false."
    }
    require(!stopAfterConsumed || stopAfterSubtaskId != null) {
      "stopAfterConsumed requires stopAfterSubtaskId."
    }
    pauseReason?.let { require(it.isNotBlank()) { "pauseReason must not be blank when provided." } }
    require(!paused || pauseReason != null) { "paused control state requires pauseReason." }
    repositoryIdentity?.let {
      require(it.isNotBlank()) { "repositoryIdentity must not be blank when provided." }
    }
  }

  fun requiresPauseBoundary(manifest: DecompositionManifest): Boolean = pauseRequested || paused || (
    stopAfterSubtaskId != null &&
      !stopAfterConsumed &&
      manifest.subtasks.any { it.id == stopAfterSubtaskId && it.status == "complete" }
    )
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

enum class GoalPlanningStatusState(val wireValue: String) {
  NOT_STARTED("not_started"),
  PREPLANNED("preplanned"),
  PARTIALLY_PLANNED("partially_planned"),
  BLOCKED("blocked"),
  PREPARED("prepared"),
}

enum class ExecutionLiveness(val wireValue: String) {
  LIVE("live"),
  IDLE("idle"),
  UNKNOWN("unknown"),
}

data class GoalPlanningStatusSnapshot(
  val state: GoalPlanningStatusState,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  val currentPlanningSubtaskId: Int?,
  val reason: String?,
)

data class GoalRunnerStatusProjection(
  val issueKey: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val currentSubtaskId: Int?,
  /** Launched child workflow id for [currentSubtaskId], when the subtask has one. */
  val currentChildWorkflowId: String? = null,
  val currentStep: String?,
  val activeAgent: String?,
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val latestLivenessSignal: String? = null,
  @OpenBoundaryMap("Compact latest goal observability event passthrough for goal status rendering")
  val latestObservabilityEvent: Map<String, Any?>? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val stopAfterSubtaskId: Int? = null,
)

/**
 * A subtask an operator recorded as landed outside the runtime. The git-tracked manifest projection
 * deliberately omits commit SHAs to keep that file churn-free, so this read-only status surface is
 * where a human sees which commit an accepted subtask actually points at.
 */
data class GoalRunnerAcceptedSubtask(
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val acceptedAt: String,
)

data class GoalRunnerStatusProjectionExtras(
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val currentStepOverride: String? = null,
  /**
   * Live workflow status of the current subtask's child. The manifest projection is only rewritten at
   * reconciliation points, so a subtask relaunched from a durable block still reads `blocked` there for
   * the whole run; this reports what the child is actually doing.
   */
  val currentWorkflowStatus: String? = null,
  val latestLivenessSignal: String? = null,
  @OpenBoundaryMap("Compact latest goal observability event passthrough for goal status rendering")
  val latestObservabilityEvent: Map<String, Any?>? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val stopAfterSubtaskId: Int? = null,
)

object GoalRunnerStatusProjector {
  @OpenBoundaryMap("Goal status projection accepts compact latest goal observability event passthrough")
  fun project(
    manifest: DecompositionManifest,
    activeAgent: String? = null,
    extras: GoalRunnerStatusProjectionExtras = GoalRunnerStatusProjectionExtras(),
  ): GoalRunnerStatusProjection {
    val currentSubtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
    val statusOf: (DecompositionSubtask) -> String = { subtask ->
      if (subtask.id == currentSubtask?.id && extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES) {
        "in_progress"
      } else {
        subtask.status
      }
    }
    // Only goal_runner_supervisor events are persisted; the per-tick foreground heartbeats are console-only.
    // So a block recorded when a prior run stopped stays the newest stored event while a relaunched child
    // runs, and rendering it would contradict the live workflow status.
    val staleBlockSignal = extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES &&
      extras.latestObservabilityEvent?.get("liveness_class") == "block"
    return GoalRunnerStatusProjection(
      issueKey = manifest.issueKey,
      completeCount = manifest.subtasks.count { statusOf(it) == "complete" || statusOf(it) == "skipped" },
      pendingCount = manifest.subtasks.count { statusOf(it) !in setOf("complete", "skipped", "blocked") },
      blockedCount = manifest.subtasks.count { statusOf(it) == "blocked" },
      currentSubtaskId = currentSubtask?.id,
      currentChildWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank),
      currentStep = extras.currentStepOverride?.takeIf(String::isNotBlank)
        ?: currentSubtask?.lastResumableStep
        ?: currentSubtask?.let { s -> if (s.workflowId.isNullOrBlank()) "pending_launch" else "initializing" },
      activeAgent = activeAgent?.takeIf(String::isNotBlank),
      executionLiveness = extras.executionLiveness,
      planning = extras.planning,
      latestLivenessSignal = extras.latestLivenessSignal?.takeIf { it.isNotBlank() && !staleBlockSignal },
      latestObservabilityEvent = extras.latestObservabilityEvent?.takeUnless { staleBlockSignal },
      requestedDiffStat = extras.requestedDiffStat,
      selectedDiffHunks = extras.selectedDiffHunks,
      blockedAttemptCount = extras.blockedAttemptCount,
      supervisorKillCount = extras.supervisorKillCount,
      phaseAttemptCounts = extras.phaseAttemptCounts,
      cumulativeFixIterations = extras.cumulativeFixIterations,
      reAttemptCauseCounts = extras.reAttemptCauseCounts,
      findingsInScope = extras.findingsInScope,
      outOfBandAcceptances = extras.outOfBandAcceptances,
      paused = extras.paused,
      pauseRequested = extras.pauseRequested,
      pauseReason = extras.pauseReason,
      stopAfterSubtaskId = extras.stopAfterSubtaskId,
    )
  }

  private val LIVE_WORKFLOW_STATUSES = setOf("running", "pending")
}
