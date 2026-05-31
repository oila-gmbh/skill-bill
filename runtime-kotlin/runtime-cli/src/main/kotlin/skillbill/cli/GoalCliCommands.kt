@file:Suppress("TooManyFunctions")

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
import skillbill.application.RuntimeProvenanceService
import skillbill.application.model.DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalRunCommand(
  private val goalRunner: GoalRunner,
  private val runtimeProvenanceService: RuntimeProvenanceService,
  goalStatusCommand: GoalStatusCommand,
  goalResetCommand: GoalResetCommand,
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
  private val debugChildOutput by option(
    "--debug-child-output",
    help = "Show full child stdout/stderr instead of only structured skill-bill lines.",
  ).flag(default = false)

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(goalStatusCommand, goalResetCommand)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for goal run.")
    val presenter = GoalRunPresenter(
      issueKey = runIssueKey,
      state = state,
      liveOutput = !noLiveOutput,
      runtimeProvenance = runtimeProvenanceService.current(
        executablePathHint = state.environment[RUNTIME_EXECUTABLE_ENV],
        classPath = state.environment[RUNTIME_CLASSPATH_ENV] ?: System.getProperty("java.class.path").orEmpty(),
        javaCommand = ProcessHandle.current().info().command().orElse(null),
        pathSeparator = state.environment[RUNTIME_PATH_SEPARATOR_ENV] ?: System.getProperty("path.separator", ":"),
      ),
    )
    presenter.emitStartupProvenance()
    val report = goalRunner.run(
      GoalRunnerRunRequest(
        issueKey = runIssueKey,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
        invokedAgentId = agent ?: state.environment["SKILL_BILL_AGENT"] ?: DEFAULT_GOAL_AGENT,
        configuredAgentOverrideId = agentOverride,
        dbPathOverride = state.dbOverride,
        timeout = maxWallClockMinutes?.minutes,
        progressIdleTimeout = progressIdleTimeoutMinutes.minutes,
        outputSink = presenter.outputSink(includeRawChildOutput = debugChildOutput),
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

@Inject
class GoalResetCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("reset", "Reset decomposed goal runtime state.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val hard by option("--hard", help = "Reset all subtask runtime fields, including completed subtasks.")
    .flag(default = false)
  private val force by option("--force", help = "Bypass hard-reset confirmation gate.")
    .flag(default = false)
  private val confirmIssueKey by option(
    "--confirm-issue-key",
    help = "Confirmation gate for --hard. Must match the issue key.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")

  override fun run() {
    if (hard && !force && confirmIssueKey != issueKey) {
      throw UsageError(
        "Hard reset requires explicit confirmation. Pass --confirm-issue-key $issueKey or --force.",
      )
    }
    val result = goalRunnerStatusService.reset(
      GoalRunnerResetRequest(
        issueKey = issueKey,
        hard = hard,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
      ),
    )
    val payload = result.toGoalResetCliMap(issueKey, hard)
    state.completeText(goalResetText(payload), payload, exitCode = payload.goalResetExitCode())
  }
}

private class GoalRunPresenter(
  private val issueKey: String,
  private val state: CliRunState,
  private val liveOutput: Boolean,
  private val runtimeProvenance: RuntimeProvenanceContract,
) {
  private var activeSubtaskId: Int? = null
  private var activeStepId: String? = null
  private var lastLivenessClass: String = GOAL_LIVENESS_IDLE
  private var sawRawChildOutputSinceLastHeartbeat: Boolean = false

  fun emitStartupProvenance() {
    state.liveStdout(
      "goal $issueKey: runtime executable=${runtimeProvenance.executablePath} " +
        "version=${runtimeProvenance.version} build_id=${runtimeProvenance.buildId}\n",
    )
  }

  fun eventSink(): skillbill.application.model.GoalRunnerEventSink =
    skillbill.application.model.GoalRunnerEventSink { event ->
      when (event) {
        is GoalRunnerRunEvent.SubtaskStarted -> {
          activeSubtaskId = event.subtaskId
          activeStepId = "preplan"
          lastLivenessClass = GOAL_LIVENESS_IDLE
          sawRawChildOutputSinceLastHeartbeat = false
        }
        is GoalRunnerRunEvent.SubtaskCompleted -> {
          activeSubtaskId = event.subtaskId
          activeStepId = "commit_push"
          lastLivenessClass = GOAL_LIVENESS_DURABLE_PROGRESS
          sawRawChildOutputSinceLastHeartbeat = false
        }
        else -> Unit
      }
      state.liveStdout(event.progressLine())
    }

  fun outputSink(includeRawChildOutput: Boolean): AgentRunOutputSink = if (!liveOutput) {
    AgentRunOutputSink.NONE
  } else {
    AgentRunOutputSink { stream, text ->
      if (includeRawChildOutput) {
        when (stream) {
          AgentRunOutputStream.STDOUT -> state.liveStdout(text)
          AgentRunOutputStream.STDERR -> state.liveStderr(text)
        }
        return@AgentRunOutputSink
      }
      handleStructuredProgressText(text)
    }
  }

  private fun handleStructuredProgressText(text: String) {
    text.lines().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isBlank()) {
        return@forEach
      }
      when {
        line.startsWith("skill-bill: workflow progress:") -> handleWorkflowProgressLine(line)
        line.startsWith("skill-bill: file activity observed;") -> lastLivenessClass = GOAL_LIVENESS_FILE_ACTIVITY
        line.startsWith("skill-bill: status heartbeat") -> emitStructuredHeartbeat()
        !line.startsWith("skill-bill:") -> sawRawChildOutputSinceLastHeartbeat = true
      }
    }
  }

  private fun handleWorkflowProgressLine(line: String) {
    val label = line.substringAfter("skill-bill: workflow progress:").trim()
    parseSubtaskAndStepFromLabel(label)
    lastLivenessClass = when {
      "durable_progress" in label -> GOAL_LIVENESS_DURABLE_PROGRESS
      "file activity" in label.lowercase() -> GOAL_LIVENESS_FILE_ACTIVITY
      else -> lastLivenessClass
    }
  }

  private fun emitStructuredHeartbeat() {
    val heartbeatLiveness = when {
      lastLivenessClass == GOAL_LIVENESS_DURABLE_PROGRESS -> GOAL_LIVENESS_DURABLE_PROGRESS
      lastLivenessClass == GOAL_LIVENESS_FILE_ACTIVITY -> GOAL_LIVENESS_FILE_ACTIVITY
      sawRawChildOutputSinceLastHeartbeat -> GOAL_LIVENESS_OUTPUT_ONLY
      else -> GOAL_LIVENESS_IDLE
    }
    val subtask = activeSubtaskId?.toString() ?: "unknown"
    val step = activeStepId ?: "unknown"
    state.liveStdout(
      "goal $issueKey: heartbeat subtask=$subtask step=$step liveness=$heartbeatLiveness\n",
    )
    sawRawChildOutputSinceLastHeartbeat = false
    if (heartbeatLiveness != GOAL_LIVENESS_DURABLE_PROGRESS) {
      lastLivenessClass = heartbeatLiveness
    }
  }

  private fun parseSubtaskAndStepFromLabel(label: String) {
    GOAL_SUBTASK_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { subtaskId ->
      activeSubtaskId = subtaskId
    }
    GOAL_STEP_REGEX.find(label)?.groupValues?.getOrNull(1)?.let { stepId ->
      activeStepId = stepId
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
    append("goal $issueKey: completion confirmed")
    append(" complete=")
    append(completedCount)
    append(" pending=")
    append(pendingCount)
    append(" blocked=")
    append(blockedCount)
    append(" pr_status=")
    append(pullRequestStatus)
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
    "subtasks_pending" to subtasksPending,
    "subtasks_blocked" to subtasksBlocked,
    "pull_request_status" to pullRequestStatus,
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
  payload["subtasks_pending"]?.let { appendLine("subtasks_pending: $it") }
  payload["subtasks_blocked"]?.let { appendLine("subtasks_blocked: $it") }
  payload["pull_request_status"]?.let { appendLine("pull_request_status: $it") }
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
    "latest_liveness_signal" to it.latestLivenessSignal,
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
  "latest_liveness_signal" to null,
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
  appendLine("latest_liveness_signal: ${payload["latest_liveness_signal"] ?: "none"}")
}

private fun Map<String, Any?>.goalStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

private fun GoalRunnerResetResult?.toGoalResetCliMap(issueKey: String, hard: Boolean): Map<String, Any?> = this?.let {
  linkedMapOf(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "mode" to it.mode,
    "parent_workflow_id" to it.parentWorkflowId,
    "before" to resetSnapshotMap(it.before),
    "after" to resetSnapshotMap(it.after),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "mode" to if (hard) "hard" else "soft",
)

private fun resetSnapshotMap(snapshot: skillbill.application.model.GoalRunnerResetSnapshot): Map<String, Any?> =
  linkedMapOf(
    "status" to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "subtasks" to snapshot.subtasks.map { subtask ->
      linkedMapOf(
        "id" to subtask.id,
        "status" to subtask.status,
        "branch" to subtask.branch,
        "workflow_id" to subtask.workflowId,
        "commit_sha" to subtask.commitSha,
        "blocked_reason" to subtask.blockedReason,
        "last_resumable_step" to subtask.lastResumableStep,
      )
    },
  )

private fun goalResetText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("mode: ${payload["mode"]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  val before = payload["before"] as? Map<*, *>
  val after = payload["after"] as? Map<*, *>
  if (before != null && after != null) {
    appendLine("before: status=${before["status"]}; current_subtask=${before["current_subtask"] ?: "none"}")
    appendLine("after: status=${after["status"]}; current_subtask=${after["current_subtask"] ?: "none"}")
    appendLine("before_subtasks:")
    appendGoalResetSubtaskLines(this, before["subtasks"] as? List<*>)
    appendLine("after_subtasks:")
    appendGoalResetSubtaskLines(this, after["subtasks"] as? List<*>)
  }
}

private const val DEFAULT_GOAL_AGENT = "codex"
private const val GOAL_LIVENESS_DURABLE_PROGRESS = "durable_progress"
private const val GOAL_LIVENESS_FILE_ACTIVITY = "file_activity"
private const val GOAL_LIVENESS_OUTPUT_ONLY = "output_only"
private const val GOAL_LIVENESS_IDLE = "idle"
private const val RUNTIME_EXECUTABLE_ENV = "SKILL_BILL_RUNTIME_EXECUTABLE"
private const val RUNTIME_CLASSPATH_ENV = "SKILL_BILL_RUNTIME_CLASSPATH"
private const val RUNTIME_PATH_SEPARATOR_ENV = "SKILL_BILL_PATH_SEPARATOR"
private val GOAL_SUBTASK_REGEX = Regex("""\bsubtask\s+(\d+)\b""")
private val GOAL_STEP_REGEX = Regex("""\bstep\s+([a-zA-Z0-9_-]+)\b""")
