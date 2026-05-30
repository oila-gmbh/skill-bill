package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.GoalRunner
import skillbill.application.GoalRunnerStatusService
import skillbill.application.model.DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalRunCommand(
  private val goalRunner: GoalRunner,
  goalStatusCommand: GoalStatusCommand,
  private val state: CliRunState,
) : DocumentedCliCommand("goal", "Run a decomposed goal in the foreground.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.").optional()
  private val agent by option(
    "--agent",
    help = "Agent invoking bill-goal. Defaults to SKILL_BILL_AGENT or codex.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent to use for child subtask runs instead of the invoking agent.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for child agent runs.")
  private val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Optional per-subtask wall-clock cap in minutes. Default is no wall-clock cap.",
  ).int()
  private val progressIdleTimeoutMinutes by option(
    "--progress-idle-timeout-minutes",
    help = "Per-subtask durable workflow-progress idle timeout in minutes.",
  ).int()
    .default(DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT.inWholeMinutes.toInt())
  private val noLiveOutput by option(
    "--no-live-output",
    help = "Do not tee child stdout and stderr to this terminal.",
  ).flag(default = false)

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(goalStatusCommand)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for goal run.")
    val presenter = GoalRunPresenter(state, liveOutput = !noLiveOutput)
    val report = goalRunner.run(
      GoalRunnerRunRequest(
        issueKey = runIssueKey,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
        invokedAgentId = agent ?: state.environment["SKILL_BILL_AGENT"] ?: DEFAULT_GOAL_AGENT,
        configuredAgentOverrideId = agentOverride,
        dbPathOverride = state.dbOverride,
        timeout = maxWallClockMinutes?.minutes,
        progressIdleTimeout = progressIdleTimeoutMinutes.minutes,
        outputSink = presenter.outputSink(),
        eventSink = presenter.eventSink(),
      ),
    )
    val payload = report.toGoalRunCliMap()
    state.completeText(goalRunText(payload), payload, exitCode = payload.goalExitCode())
  }
}

@Inject
class GoalStatusCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only decomposed goal status.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val agent by option(
    "--agent",
    help = "Agent invoking bill-goal. Defaults to SKILL_BILL_AGENT or codex.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent override whose id should be shown as active.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")

  override fun run() {
    val projection = goalRunnerStatusService.status(
      GoalRunnerStatusRequest(
        issueKey = issueKey,
        invokedAgentId = agent ?: state.environment["SKILL_BILL_AGENT"] ?: DEFAULT_GOAL_AGENT,
        configuredAgentOverrideId = agentOverride,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
      ),
    )
    val payload = projection.toGoalStatusCliMap(issueKey)
    state.completeText(goalStatusText(payload), payload, exitCode = payload.goalStatusExitCode())
  }
}

private class GoalRunPresenter(
  private val state: CliRunState,
  private val liveOutput: Boolean,
) {
  fun eventSink(): skillbill.application.model.GoalRunnerEventSink =
    skillbill.application.model.GoalRunnerEventSink { event ->
      state.liveStdout(event.progressLine())
    }

  fun outputSink(): AgentRunOutputSink = if (!liveOutput) {
    AgentRunOutputSink.NONE
  } else {
    AgentRunOutputSink { stream, text ->
      when (stream) {
        AgentRunOutputStream.STDOUT -> state.liveStdout(text)
        AgentRunOutputStream.STDERR -> state.liveStderr(text)
      }
    }
  }
}

private fun GoalRunnerRunEvent.progressLine(): String = when (this) {
  is GoalRunnerRunEvent.Started -> "goal $issueKey: started\n"
  is GoalRunnerRunEvent.SubtaskStarted -> "goal $issueKey: subtask $subtaskId $action\n"
  is GoalRunnerRunEvent.SubtaskCompleted -> "goal $issueKey: subtask $subtaskId complete\n"
  is GoalRunnerRunEvent.SubtaskStopped ->
    "goal $issueKey: subtask $subtaskId stopped ($reason): $blockedReason\n"
  is GoalRunnerRunEvent.Completed -> buildString {
    append("goal $issueKey: complete")
    pullRequestUrl?.let { append(" ($it)") }
    append('\n')
  }
}

private fun GoalRunnerRunReport.toGoalRunCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "attempted_subtasks" to attemptedSubtasks,
    "subtasks_completed" to subtasksCompleted,
    "pull_request_url" to pullRequestUrl,
  )
  is GoalRunnerRunReport.Stopped -> linkedMapOf(
    "status" to "stopped",
    "issue_key" to issueKey,
    "attempted_subtasks" to attemptedSubtasks,
    "subtask_id" to stop.subtaskId,
    "reason" to stop.reason.name.lowercase(),
    "blocked_reason" to stop.blockedReason,
    "workflow_id" to stop.workflowId,
    "last_resumable_step" to stop.lastResumableStep,
  )
}

private fun goalRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("attempted_subtasks: ${(payload["attempted_subtasks"] as? List<*>).orEmpty().joinToString()}")
  payload["subtasks_completed"]?.let { appendLine("subtasks_completed: $it") }
  payload["pull_request_url"]?.let { appendLine("pull_request_url: $it") }
  payload["subtask_id"]?.let { appendLine("subtask_id: $it") }
  payload["reason"]?.let { appendLine("reason: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
  payload["workflow_id"]?.let { appendLine("workflow_id: $it") }
  payload["last_resumable_step"]?.let { appendLine("last_resumable_step: $it") }
}

private fun Map<String, Any?>.goalExitCode(): Int = if (this["status"] == "complete") 0 else 1

private fun GoalRunnerStatusProjection?.toGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep,
    "active_agent" to it.activeAgent,
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "complete_count" to 0,
  "pending_count" to 0,
  "blocked_count" to 0,
  "current_subtask" to null,
  "current_step" to null,
  "active_agent" to null,
)

private fun goalStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
  appendLine("current_step: ${payload["current_step"] ?: "none"}")
  appendLine("active_agent: ${payload["active_agent"] ?: "none"}")
}

private fun Map<String, Any?>.goalStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

private const val DEFAULT_GOAL_AGENT = "codex"
