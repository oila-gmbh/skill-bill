package skillbill.application.model

import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_LINES
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
  val progressIdleTimeout: Duration = DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  val observabilitySequenceStart: Int = DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    require(progressIdleTimeout.isPositive()) { "progressIdleTimeout must be positive." }
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
  ) : GoalRunnerRunEvent

  data class SubtaskCompleted(
    override val issueKey: String,
    val subtaskId: Int,
  ) : GoalRunnerRunEvent

  data class SubtaskStopped(
    override val issueKey: String,
    val subtaskId: Int,
    val reason: String,
    val blockedReason: String,
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

val DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT: Duration = 30.minutes
const val DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START: Int = 10_000

fun interface GoalRunnerEventSink {
  fun emit(event: GoalRunnerRunEvent)

  companion object {
    val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
  }
}

data class GoalRunnerStatusRequest(
  val issueKey: String,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    require(selectedDiffHunkPaths.all { it.isNotBlank() }) { "selectedDiffHunkPaths must not contain blanks." }
    require(selectedDiffMaxHunks > 0) { "selectedDiffMaxHunks must be positive." }
    require(selectedDiffMaxLines > 0) { "selectedDiffMaxLines must be positive." }
    require(selectedDiffMaxBytes > 0) { "selectedDiffMaxBytes must be positive." }
  }
}

data class GoalRunnerResetRequest(
  val issueKey: String,
  val hard: Boolean,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerResetResult(
  val issueKey: String,
  val mode: String,
  val parentWorkflowId: String,
  val before: GoalRunnerResetSnapshot,
  val after: GoalRunnerResetSnapshot,
)

data class GoalRunnerResetSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

data class GoalRunnerResetSubtaskSnapshot(
  val id: Int,
  val status: String,
  val branch: String?,
  val workflowId: String?,
  val commitSha: String?,
  val blockedReason: String?,
  val lastResumableStep: String?,
)
