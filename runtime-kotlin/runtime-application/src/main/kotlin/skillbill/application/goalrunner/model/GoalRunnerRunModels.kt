package skillbill.application.goalrunner.model

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GoalRunnerRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val dbPathOverride: String? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val planningBudget: Duration? = DEFAULT_GOAL_PLANNING_BUDGET,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val stopAfterSubtaskId: Int? = null,
  val observabilitySequenceStart: Int = DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive when provided." }
    }
    planningBudget?.let { budget ->
      require(budget.isPositive()) { "planningBudget must be positive when provided." }
    }
    require(observabilitySequenceStart >= 0) { "observabilitySequenceStart must be non-negative." }
  }
}

sealed interface GoalRunnerRunEvent {
  val issueKey: String

  data class Started(override val issueKey: String) : GoalRunnerRunEvent

  data class SubtaskStarted(
    override val issueKey: String,
    val subtaskId: Int,
    val action: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskCompleted(
    override val issueKey: String,
    val subtaskId: Int,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskStopped(
    override val issueKey: String,
    val subtaskId: Int,
    val reason: String,
    val blockedReason: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskReviewSummary(
    override val issueKey: String,
    val subtaskId: Int,
    val passNumber: Int,
    val verdict: String,
    val findingCount: Int,
    val unresolvedFindingCount: Int,
    val findings: List<GoalSubtaskReviewCompactFinding>,
  ) : GoalRunnerRunEvent

  data class Completed(
    override val issueKey: String,
    val completedCount: Int,
    val pendingCount: Int,
    val blockedCount: Int,
    val pullRequestStatus: String,
    val pullRequestUrl: String?,
  ) : GoalRunnerRunEvent
}

val DEFAULT_GOAL_PLANNING_BUDGET: Duration = 30.minutes

const val DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START: Int = 10_000

const val DEFAULT_GOAL_EVENT_SEQUENCE_START: Int = 20_000

fun interface GoalRunnerEventSink {
  fun emit(event: GoalRunnerRunEvent)

  companion object {
    val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
  }
}
