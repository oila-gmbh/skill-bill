package skillbill.application.model

import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import java.nio.file.Path
import kotlin.time.Duration

data class GoalRunnerRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val dbPathOverride: String? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  /** Null means reuse the parent goal's durable mode, or AUTO for a new parent. */
  val codeReviewMode: CodeReviewExecutionMode? = null,
  /** Null means reuse the parent goal's durable parallel lane, or run one lane for a new parent. */
  val parallelReviewAgent: String? = null,
  val observabilitySequenceStart: Int = DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    parallelReviewAgent?.let { require(it.isNotBlank()) { "parallelReviewAgent must not be blank." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive when provided." }
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
    // SKILL-64 Subtask 3 (AC24): authoritative durable step from the workflow
    // store, never a hardcoded local default. Null only before a durable step
    // exists for the child.
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

const val DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START: Int = 10_000

// SKILL-64 Subtask 3 (AC16): goal_event: transition sequence space, distinct
// from the goal_observability sequence space (DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START).
const val DEFAULT_GOAL_EVENT_SEQUENCE_START: Int = 20_000

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
