package skillbill.ports.agentrun.model

import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.install.model.InstallAgent
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import java.nio.file.Path
import kotlin.time.Duration

data class SkillRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val subtaskId: Int? = null,
  val dbPathOverride: String? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  // SKILL-64 Subtask 3 (AC20): authoritative declared-progress probe. The
  // legacy [progressProbe] is retained but demoted to a non-authoritative hint.
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  // SKILL-64 Subtask 3 (AC21, AC25): supervisor-side declared-progress emitter.
  // The process-lifecycle wrapper drives this on child launch, each gated
  // heartbeat tick, and child exit/timeout/interrupt/kill so the declared
  // operation_* events are produced WITHOUT the child phase-agent self-
  // reporting. Defaults to a no-op so callers that do not wire it (AC15) keep
  // their existing behavior.
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    subtaskId?.let { id -> require(id > 0) { "subtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive." }
    }
  }
}

fun interface AgentRunProgressProbe {
  fun progressToken(): String?

  fun progressLabel(): String? = null

  companion object {
    val NONE: AgentRunProgressProbe = AgentRunProgressProbe { null }
  }
}

/**
 * SKILL-64 Subtask 3 (AC20-AC23): authoritative declared-progress probe. The
 * supervisor reads the latest durable [GoalProgressEvent] plus a process-alive
 * signal and decides liveness/idle-timeout outcomes deterministically from
 * these declared facts. Returns `null` when no declared event exists yet.
 */
fun interface AgentRunDeclaredProgressProbe {
  fun latestDeclaredProgress(): AgentRunDeclaredProgressSnapshot?

  companion object {
    val NONE: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe { null }
  }
}

/**
 * Typed declared-progress snapshot: the latest declared event and the
 * authoritative process-alive signal observed alongside it.
 */
data class AgentRunDeclaredProgressSnapshot(
  val latestEvent: GoalProgressEvent,
  val processAlive: Boolean,
)

/**
 * SKILL-64 Subtask 3 (AC21, AC25): supervisor-side declared-progress emitter
 * port. The process-lifecycle wrapper (the sole owner of the child process)
 * calls [emit] at child launch, on each gated heartbeat tick, and at child
 * exit/timeout/interrupt/kill. The port is intentionally effect-free at the
 * type level: it does NOT mint timestamps or sequence numbers. The adapter
 * implementation (application layer) mints the timestamp, seeds the monotonic
 * goal_progress sequence, resolves the child workflow id, and persists the
 * declared [GoalProgressEvent] best-effort. A write failure must never fail the
 * run.
 */
fun interface AgentRunProgressEmitter {
  fun emit(emission: AgentRunProgressEmission)

  companion object {
    val NONE: AgentRunProgressEmitter = AgentRunProgressEmitter { }
  }
}

/**
 * Effect-free declared-progress emission produced by the process-lifecycle
 * wrapper. Carries the authoritative process-alive signal and the long-
 * operation descriptors; the adapter mints timestamp/sequence and resolves the
 * workflow id/phase/step before persisting.
 */
data class AgentRunProgressEmission(
  val eventKind: GoalProgressEventKind,
  val processAlive: Boolean,
  val operationName: String,
  val operationKind: String,
  val expectedLong: Boolean = true,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
)

enum class AgentRunOutputStream {
  STDOUT,
  STDERR,
}

fun interface AgentRunOutputSink {
  fun write(stream: AgentRunOutputStream, text: String)

  companion object {
    val NONE: AgentRunOutputSink = AgentRunOutputSink { _, _ -> }
  }
}

data class AgentRunLaunchRequest(
  val agentId: String,
  val skillRunRequest: SkillRunRequest,
) {
  init {
    require(agentId.isNotBlank()) { "agentId is required." }
  }
}

sealed interface AgentRunLaunchOutcome {
  val agent: InstallAgent
}

data class AgentRunLivenessSnapshot(
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
  // SKILL-64 Subtask 3 (AC20-AC23): resolved deterministic liveness taxonomy
  // and the declared active-operation context that produced it.
  val livenessState: GoalRunnerLivenessState? = null,
  val activeOperationName: String? = null,
  val activeOperationKind: String? = null,
  val activeOperationExpectedLong: Boolean = false,
  val operationDeadline: String? = null,
)

data class AgentRunLaunchFacts(
  override val agent: InstallAgent,
  val exitStatus: Int?,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean,
  val interrupted: Boolean = false,
  val spawnFailed: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
  // SKILL-64 Subtask 3 (AC6, AC11): provider-NEUTRAL child-session descriptors
  // populated by the launcher from the launch command context (working dir / a
  // session marker the launcher itself controls). These are NOT parsed from any
  // provider-private token-log format (Non-Goal): they let the durable session
  // accounting and the attempt ledger carry a determinable child session
  // path/id when one is available.
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
) : AgentRunLaunchOutcome {
  init {
    require(!timedOut || exitStatus == null) { "timedOut launch facts must not report an exitStatus." }
    require(!interrupted || exitStatus == null) { "interrupted launch facts must not report an exitStatus." }
    require(!spawnFailed || exitStatus == null) { "spawnFailed launch facts must not report an exitStatus." }
  }
}

data class UnsupportedAgentRunLaunch(
  override val agent: InstallAgent,
  val reason: String,
) : AgentRunLaunchOutcome {
  init {
    require(reason.isNotBlank()) { "Unsupported-agent reason is required." }
  }
}
