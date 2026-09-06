package skillbill.application.goalrunner.model

import skillbill.agent.model.AgentId
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GoalRunnerRunRequest(
  val issueKey: IssueKey,
  val repoRoot: Path,
  val invokedAgentId: AgentId,
  val configuredAgentOverrideId: String? = null,
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
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.value.isNotBlank()) { "invokedAgentId is required." }
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
  val issueKey: IssueKey

  data class Started(override val issueKey: IssueKey) : GoalRunnerRunEvent

  data class SubtaskStarted(
    override val issueKey: IssueKey,
    val subtaskId: SubtaskId,
    val action: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskCompleted(
    override val issueKey: IssueKey,
    val subtaskId: SubtaskId,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskStopped(
    override val issueKey: IssueKey,
    val subtaskId: SubtaskId,
    val reason: String,
    val blockedReason: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskReviewSummary(
    override val issueKey: IssueKey,
    val subtaskId: SubtaskId,
    val passNumber: Int,
    val verdict: String,
    val findingCount: Int,
    val unresolvedFindingCount: Int,
    val findings: List<GoalSubtaskReviewCompactFinding>,
  ) : GoalRunnerRunEvent

  data class Completed(
    override val issueKey: IssueKey,
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
