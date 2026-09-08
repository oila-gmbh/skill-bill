package skillbill.goalrunner.model

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
