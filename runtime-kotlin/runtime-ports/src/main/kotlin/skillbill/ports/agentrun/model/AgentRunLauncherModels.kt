@file:Suppress("MaxLineLength")

package skillbill.ports.agentrun.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.install.model.InstallAgent
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.CodeReviewExecutionMode
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
  val modelOverride: String? = null,
  val effortOverride: String? = null,
  val goalContinuation: SkillRunGoalContinuationContext? = null,
  val conversationIsolation: ConversationIsolation? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    promptOverride?.let { prompt -> require(prompt.isNotBlank()) { "promptOverride must be non-blank when provided." } }
    modelOverride?.let { model -> require(model.isNotBlank()) { "modelOverride must be non-blank when provided." } }
    effortOverride?.let { effort -> require(effort.isNotBlank()) { "effortOverride must be non-blank when provided." } }
    subtaskId?.let { id -> require(id > 0) { "subtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive." }
    }
    require(reviewEvidenceBroker == null || conversationIsolation == ConversationIsolation.NONE) {
      "A review evidence transport is valid only for a fresh-context governed specialist launch."
    }
  }
}

enum class ConversationIsolation(val forkTurns: String) {
  NONE("none"),
}

/**
 * Named per-agent isolation strategy for governed review launches, resolved from the agent's own
 * command builder. The process runner never reads agent identity; it reads the strategy.
 */
enum class ReviewLaunchIsolationStrategy(val forkTurns: String?, val supported: Boolean) {
  CODEX_NATIVE_FORK_TURNS_NONE(ConversationIsolation.NONE.forkTurns, true),
  FRESH_PROCESS(null, true),
  UNSUPPORTED(null, false),
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
  val assignedWorkflowId: String? = null,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val parallelReviewAgent: String? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(parentIssueKey.isNotBlank()) { "parentIssueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "goalBranch is required." }
    require(specPath.isNotBlank()) { "specPath is required." }
    parentWorkflowId?.let { require(it.isNotBlank()) { "parentWorkflowId must be non-blank when provided." } }
    lastResumableStep?.let { require(it.isNotBlank()) { "lastResumableStep must be non-blank when provided." } }
    childWorkflowId?.let { require(it.isNotBlank()) { "childWorkflowId must be non-blank when provided." } }
    assignedWorkflowId?.let { require(it.isNotBlank()) { "assignedWorkflowId must be non-blank when provided." } }
    parallelReviewAgent?.let { require(it.isNotBlank()) { "parallelReviewAgent must be non-blank when provided." } }
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
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningTokens: Long? = null,
  val totalTokens: Long? = null,
  val tokenOwnership: AgentRunTokenOwnership = AgentRunTokenOwnership.DIRECT,
) : AgentRunLaunchOutcome {
  init {
    require(!timedOut || exitStatus == null) { "timedOut launch facts must not report an exitStatus." }
    require(!interrupted || exitStatus == null) { "interrupted launch facts must not report an exitStatus." }
    require(!spawnFailed || exitStatus == null) { "spawnFailed launch facts must not report an exitStatus." }
    require(
      listOf(inputTokens, cachedInputTokens, outputTokens, reasoningTokens, totalTokens).all {
        it == null || it >= 0
      },
    ) {
      "Provider token values cannot be negative."
    }
  }
}

enum class AgentRunTokenOwnership {
  DIRECT,
  INCLUSIVE,
}

data class UnsupportedAgentRunLaunch(
  override val agent: InstallAgent,
  val reason: String,
) : AgentRunLaunchOutcome {
  init {
    require(reason.isNotBlank()) { "Unsupported-agent reason is required." }
  }
}
