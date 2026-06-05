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
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val promptOverride: String? = null,
  val goalContinuation: SkillRunGoalContinuationContext? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    promptOverride?.let { prompt -> require(prompt.isNotBlank()) { "promptOverride must be non-blank when provided." } }
    subtaskId?.let { id -> require(id > 0) { "subtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive." }
    }
  }
}

data class SkillRunGoalContinuationContext(
  val parentIssueKey: String,
  val subtaskId: Int,
  val goalBranch: String,
  val suppressPr: Boolean,
  val specPath: String,
  val parentWorkflowId: String? = null,
  val lastResumableStep: String? = null,
  val childWorkflowId: String? = null,
) {
  init {
    require(parentIssueKey.isNotBlank()) { "parentIssueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "goalBranch is required." }
    require(specPath.isNotBlank()) { "specPath is required." }
    parentWorkflowId?.let { require(it.isNotBlank()) { "parentWorkflowId must be non-blank when provided." } }
    lastResumableStep?.let { require(it.isNotBlank()) { "lastResumableStep must be non-blank when provided." } }
    childWorkflowId?.let { require(it.isNotBlank()) { "childWorkflowId must be non-blank when provided." } }
  }
}

fun interface AgentRunProgressProbe {
  fun progressToken(): String?

  fun progressLabel(): String? = null

  companion object {
    val NONE: AgentRunProgressProbe = AgentRunProgressProbe { null }
  }
}

fun interface AgentRunDeclaredProgressProbe {
  fun latestDeclaredProgress(): AgentRunDeclaredProgressSnapshot?

  companion object {
    val NONE: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe { null }
  }
}

data class AgentRunDeclaredProgressSnapshot(
  val latestEvent: GoalProgressEvent,
  val processAlive: Boolean,
)

fun interface AgentRunProgressEmitter {
  fun emit(emission: AgentRunProgressEmission)

  companion object {
    val NONE: AgentRunProgressEmitter = AgentRunProgressEmitter { }
  }
}

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
