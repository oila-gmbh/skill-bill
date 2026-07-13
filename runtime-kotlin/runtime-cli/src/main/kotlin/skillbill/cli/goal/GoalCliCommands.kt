@file:Suppress("TooManyFunctions")

package skillbill.cli.goal

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.model.DEFAULT_GOAL_EVENT_SEQUENCE_START
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.system.RuntimeProvenanceService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.refuseRuntimeRefusedAgents
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.install.model.InstallAgent
import skillbill.review.CodeReviewExecutionMode
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalRunCommand(
  private val goalRunner: GoalRunner,
  private val runtimeProvenanceService: RuntimeProvenanceService,
  goalStatusCommand: GoalStatusCommand,
  goalWatchCommand: GoalWatchCommand,
  goalResetCommand: GoalResetCommand,
  private val state: CliRunState,
) : DocumentedCliCommand("goal", "Run a decomposed goal in the foreground.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.").optional()
  private val agent by option(
    "--agent",
    help = "Agent invoking bill-feature-goal. Resolution order: --agent, then SKILL_BILL_AGENT, then the " +
      "detected invoking-agent execution context, then a documented last-resort default ($DEFAULT_GOAL_AGENT).",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Agent to use for child subtask runs instead of the invoking agent. Wins over --agent and detection.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for child agent runs.")
  private val codeReviewMode by option(
    "--code-review-mode",
    help = "Review execution mode for every child: auto (default), inline, or delegated.",
  )
  private val parallelReviewAgent by option(
    "--parallel-review-agent",
    help = "Run every child review with a second parallel agent lane. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
  )
  private val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Optional per-subtask wall-clock cap in minutes. Default is no wall-clock cap.",
  ).int()
  private val progressIdleTimeoutMinutes by option(
    "--progress-idle-timeout-minutes",
    help = "Optional per-subtask durable workflow-progress idle timeout in minutes. " +
      "Default is no idle timeout: a subtask is never killed for taking long.",
  ).int()
  private val noLiveOutput by option(
    "--no-live-output",
    help = "Do not tee child stdout/stderr or structured observability lines to this terminal.",
  ).flag(default = false)
  private val debugChildOutput by option(
    "--debug-child-output",
    help = "Show full child stdout/stderr. Noisy; default output keeps raw child streams hidden.",
  ).flag(default = false)

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(goalStatusCommand, goalWatchCommand, goalResetCommand)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    // opencode is prose-only: refuse before any child subprocess is spawned, and before the
    // issue_key check so the actionable refusal wins over a generic argument error (mirrors
    // feature-task, where the preflight is the first statement in every run body).
    refuseRuntimeRefusedAgents(
      listOf(resolveInvokedAgentId(agent, state.environment), agentOverride, parallelReviewAgent?.takeIf(String::isNotBlank)),
    )
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
        invokedAgentId = resolveInvokedAgentId(agent, state.environment),
        configuredAgentOverrideId = agentOverride,
        dbPathOverride = state.dbOverride,
        timeout = maxWallClockMinutes?.minutes,
        progressIdleTimeout = progressIdleTimeoutMinutes?.minutes,
        outputSink = presenter.outputSink(includeRawChildOutput = debugChildOutput),
        eventSink = presenter.eventSink(),
        codeReviewMode = parseCodeReviewMode(codeReviewMode),
        parallelReviewAgent = parallelReviewAgent?.takeIf(String::isNotBlank),
      ),
    )
    val payload = report.toGoalRunCliMap()
    state.completeText(goalRunText(payload), payload, exitCode = payload.goalExitCode())
  }
}

private fun parseCodeReviewMode(raw: String?): CodeReviewExecutionMode? = raw?.let { value ->
  try {
    CodeReviewExecutionMode.fromWire(value)
  } catch (error: IllegalArgumentException) {
    throw UsageError(error.message.orEmpty())
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
    help = "Agent invoking bill-feature-goal. Resolution order: --agent, then SKILL_BILL_AGENT, then the " +
      "detected invoking-agent execution context, then a documented last-resort default.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent override whose id should be shown as active.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val diffStat by option(
    "--diff-stat",
    help = "Include one current worktree diff stat snapshot. Runs git diff --numstat once.",
  ).flag(default = false)
  private val diffHunks by option(
    "--diff-hunk",
    help = "Include bounded selected diff hunks for this path. Repeat for multiple paths; noisier than --diff-stat.",
  ).multiple()
  private val diffHunkMaxHunks by option(
    "--diff-hunk-max-hunks",
    help = "Maximum selected diff hunks to print when --diff-hunk is used.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_HUNKS)
  private val diffHunkMaxLines by option(
    "--diff-hunk-max-lines",
    help = "Maximum selected diff lines to print across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_LINES)
  private val diffHunkMaxBytes by option(
    "--diff-hunk-max-bytes",
    help = "Maximum selected diff bytes to print across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_BYTES)

  override fun run() {
    val projection = goalRunnerStatusService.status(
      state.goalStatusRequest(statusCliRequestOptions()),
    )
    val payload = projection.toGoalStatusCliMap(issueKey)
    state.completeText(goalStatusText(payload), payload, exitCode = payload.goalStatusExitCode())
  }

  private fun statusCliRequestOptions(): GoalStatusCliRequestOptions = GoalStatusCliRequestOptions(
    issueKey = issueKey,
    agent = agent,
    agentOverride = agentOverride,
    repoRoot = repoRoot,
    diff = GoalStatusCliDiffOptions(
      includeDiffStat = diffStat,
      selectedDiffHunkPaths = diffHunks,
      selectedDiffMaxHunks = diffHunkMaxHunks,
      selectedDiffMaxLines = diffHunkMaxLines,
      selectedDiffMaxBytes = diffHunkMaxBytes,
    ),
  )
}

@Inject
class GoalWatchCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("watch", "Refresh decomposed goal status without starting child runs.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val agent by option(
    "--agent",
    help = "Agent invoking bill-feature-goal. Resolution order: --agent, then SKILL_BILL_AGENT, then the " +
      "detected invoking-agent execution context, then a documented last-resort default.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent override whose id should be shown as active.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val diffStat by option(
    "--diff-stat",
    help = "Include one current worktree diff stat snapshot per refresh. " +
      "Runs git diff --numstat each refresh.",
  ).flag(default = false)
  private val diffHunks by option(
    "--diff-hunk",
    help = "Include bounded selected diff hunks for this path on each refresh. " +
      "Repeat for multiple paths; can be noisy.",
  ).multiple()
  private val diffHunkMaxHunks by option(
    "--diff-hunk-max-hunks",
    help = "Maximum selected diff hunks to print per refresh when --diff-hunk is used.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_HUNKS)
  private val diffHunkMaxLines by option(
    "--diff-hunk-max-lines",
    help = "Maximum selected diff lines to print per refresh across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_LINES)
  private val diffHunkMaxBytes by option(
    "--diff-hunk-max-bytes",
    help = "Maximum selected diff bytes to print per refresh across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_BYTES)
  private val intervalSeconds by option(
    "--interval-seconds",
    help = "Seconds between read-only status refreshes. Lower values increase terminal noise and repeated git cost.",
  ).int().default(DEFAULT_GOAL_WATCH_INTERVAL_SECONDS)
  private val maxRefreshes by option(
    "--max-refreshes",
    help = "Stop after this many refreshes. Defaults to one refresh for non-interactive automation.",
  ).int().default(DEFAULT_GOAL_WATCH_REFRESHES)

  override fun run() {
    require(intervalSeconds >= 0) { "--interval-seconds must be non-negative." }
    require(maxRefreshes > 0) { "--max-refreshes must be positive." }
    var latestRefresh: Map<String, Any?>? = null
    for (refreshIndex in 1..maxRefreshes) {
      val projection = goalRunnerStatusService.statusRefresh(
        state.goalStatusRequest(statusCliRequestOptions()),
      )
      val refresh = projection.toGoalStatusCliMap(issueKey).withWatchRefresh(refreshIndex)
      latestRefresh = refresh
      if (refreshIndex < maxRefreshes) {
        state.liveStdout(goalWatchRefreshText(refresh))
      }
      if (refreshIndex < maxRefreshes && intervalSeconds > 0) {
        Thread.sleep(intervalSeconds * MILLIS_PER_SECOND)
      }
    }
    val payload = linkedMapOf<String, Any?>(
      "status" to latestRefresh?.get("status"),
      "issue_key" to issueKey,
      "refresh_count" to maxRefreshes,
      "interval_seconds" to intervalSeconds,
      "latest_refresh" to latestRefresh,
    )
    state.completeText(goalWatchText(payload), payload, exitCode = payload.goalStatusExitCode())
  }

  private fun statusCliRequestOptions(): GoalStatusCliRequestOptions = GoalStatusCliRequestOptions(
    issueKey = issueKey,
    agent = agent,
    agentOverride = agentOverride,
    repoRoot = repoRoot,
    diff = GoalStatusCliDiffOptions(
      includeDiffStat = diffStat,
      selectedDiffHunkPaths = diffHunks,
      selectedDiffMaxHunks = diffHunkMaxHunks,
      selectedDiffMaxLines = diffHunkMaxLines,
      selectedDiffMaxBytes = diffHunkMaxBytes,
    ),
  )
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
  private val lock = Any()
  private var activeSubtaskId: Int? = null
  private var activeStepId: String? = null
  private var lastLivenessClass: String = GOAL_LIVENESS_IDLE
  private var sawRawChildOutputSinceLastHeartbeat: Boolean = false
  private var observabilitySequence: Int = 0

  // SKILL-64 Subtask 3 (AC16): distinct goal_event sequence space.
  private var goalEventSequence: Int = DEFAULT_GOAL_EVENT_SEQUENCE_START
  private var lastEmittedStatus: String? = null
  private var lastEmittedStep: String? = null

  fun emitStartupProvenance() {
    state.liveStdout(
      "goal $issueKey: runtime executable=${runtimeProvenance.executablePath} " +
        "version=${runtimeProvenance.version} build_id=${runtimeProvenance.buildId}\n",
    )
  }

  fun eventSink(): skillbill.application.model.GoalRunnerEventSink =
    skillbill.application.model.GoalRunnerEventSink { event ->
      synchronized(lock) {
        // SKILL-64 Subtask 3 (AC24): derive step from the authoritative durable
        // workflow store carried on the event, never a hardcoded local default.
        when (event) {
          is GoalRunnerRunEvent.SubtaskStarted -> {
            activeSubtaskId = event.subtaskId
            activeStepId = event.currentStepId?.takeIf(String::isNotBlank) ?: activeStepId
            lastLivenessClass = GOAL_LIVENESS_IDLE
            sawRawChildOutputSinceLastHeartbeat = false
          }
          is GoalRunnerRunEvent.SubtaskCompleted -> {
            activeSubtaskId = event.subtaskId
            activeStepId = event.currentStepId?.takeIf(String::isNotBlank) ?: activeStepId
            lastLivenessClass = GOAL_LIVENESS_DURABLE_PROGRESS
            sawRawChildOutputSinceLastHeartbeat = false
          }
          is GoalRunnerRunEvent.SubtaskStopped -> {
            activeSubtaskId = event.subtaskId
            activeStepId = event.currentStepId?.takeIf(String::isNotBlank) ?: activeStepId
          }
          is GoalRunnerRunEvent.SubtaskReviewSummary -> {
            activeSubtaskId = event.subtaskId
            activeStepId = "review"
          }
          else -> Unit
        }
        state.liveStdout(event.progressLine())
        emitGoalEvent(event)
      }
    }

  // SKILL-64 Subtask 3 (AC16): machine-consumable transition stream. Emits one
  // stable-prefixed `goal_event:` line ONLY on a meaningful change (subtask
  // change, phase/step transition, blocked, failed, completion, terminal
  // reconciliation), never per heartbeat, using a monotonic sequence in a space
  // distinct from observabilitySequence.
  private fun emitGoalEvent(event: GoalRunnerRunEvent) {
    val transition = goalEventTransition(event) ?: return
    val prevStatus = lastEmittedStatus
    val prevStep = lastEmittedStep
    val subtask = transition.subtaskId?.toString() ?: activeSubtaskId?.toString() ?: "unknown"
    val step = activeStepId ?: "unknown"
    goalEventSequence += 1
    val reviewSummary = (event as? GoalRunnerRunEvent.SubtaskReviewSummary)?.let { summary ->
      " review_pass=${summary.passNumber} finding_count=${summary.findingCount} " +
        "unresolved_finding_count=${summary.unresolvedFindingCount} compact_findings=" +
        summary.findings.joinToString("|") { finding ->
          "${finding.severity}:${finding.label}:${finding.text}".replace(Regex("\\s+"), "_")
        }
    }.orEmpty()
    state.liveStdout(
      "goal_event: issue_key=$issueKey subtask_id=$subtask prev_step=${prevStep ?: "none"} " +
        "current_step=$step prev_status=${prevStatus ?: "none"} current_status=${transition.currentStatus} " +
        "event_kind=${transition.eventKind}$reviewSummary sequence_number=$goalEventSequence\n",
    )
    lastEmittedStatus = transition.currentStatus
    lastEmittedStep = step
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
      synchronized(lock) {
        handleStructuredProgressText(text)
      }
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
        line.startsWith("skill-bill: status heartbeat") -> {
          // The heartbeat line carries the current workflow label in "; workflow: <label>" —
          // parse it so activeStepId stays in sync even when workflow progress lines
          // are missed (e.g. transient null token stopped pollWorkflowProgress).
          line.substringAfter("; workflow: ", "").takeIf(String::isNotBlank)
            ?.let { workflowSection -> parseSubtaskAndStepFromLabel(workflowSection) }
          emitStructuredHeartbeat()
        }
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
    observabilitySequence += 1
    state.liveStdout(
      "goal_observability: issue_key=$issueKey subtask_id=$subtask workflow_phase=$step " +
        "worker_role=foreground liveness_class=$heartbeatLiveness sequence_number=$observabilitySequence\n",
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

// SKILL-64 Subtask 3 (AC16): maps a run event to a goal_event transition.
// Every GoalRunnerRunEvent is itself a meaningful state change; per-tick
// heartbeats are NOT run events and never reach this path.
private data class GoalEventTransition(
  val subtaskId: Int?,
  val currentStatus: String,
  val eventKind: String,
)

private fun goalEventTransition(event: GoalRunnerRunEvent): GoalEventTransition? = when (event) {
  is GoalRunnerRunEvent.Started -> GoalEventTransition(null, "started", "goal_started")
  is GoalRunnerRunEvent.SubtaskStarted ->
    GoalEventTransition(event.subtaskId, "in_progress", "subtask_${event.action}")
  is GoalRunnerRunEvent.SubtaskCompleted ->
    GoalEventTransition(event.subtaskId, "complete", "subtask_completed")
  is GoalRunnerRunEvent.SubtaskStopped ->
    GoalEventTransition(event.subtaskId, event.reason, "subtask_stopped")
  is GoalRunnerRunEvent.SubtaskReviewSummary ->
    GoalEventTransition(event.subtaskId, event.verdict, "subtask_review_summary")
  is GoalRunnerRunEvent.Completed ->
    GoalEventTransition(null, "complete", "terminal_reconciliation")
}

private fun GoalRunnerRunEvent.progressLine(): String = when (this) {
  is GoalRunnerRunEvent.Started -> "goal $issueKey: started\n"
  is GoalRunnerRunEvent.SubtaskStarted -> "goal $issueKey: subtask $subtaskId $action\n"
  is GoalRunnerRunEvent.SubtaskCompleted -> "goal $issueKey: subtask $subtaskId complete\n"
  is GoalRunnerRunEvent.SubtaskStopped ->
    "goal $issueKey: subtask $subtaskId stopped ($reason): $blockedReason\n"
  is GoalRunnerRunEvent.SubtaskReviewSummary -> buildString {
    append("goal review: subtask=")
    append(subtaskId)
    append(" pass=")
    append(passNumber)
    append(' ')
    append(verdict)
    append(" findings=")
    append(findingCount)
    append(" unresolved=")
    append(unresolvedFindingCount)
    findings.forEach { finding ->
      append("\n  ")
      append(finding.severity.replaceFirstChar(Char::uppercase))
      append(' ')
      append(finding.label)
      append(" — ")
      append(finding.text)
    }
    append('\n')
  }
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

private data class GoalStatusCliRequestOptions(
  val issueKey: String,
  val agent: String?,
  val agentOverride: String?,
  val repoRoot: String?,
  val diff: GoalStatusCliDiffOptions = GoalStatusCliDiffOptions(),
)

private data class GoalStatusCliDiffOptions(
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
)

private fun CliRunState.goalStatusRequest(options: GoalStatusCliRequestOptions): GoalRunnerStatusRequest =
  GoalRunnerStatusRequest(
    issueKey = options.issueKey,
    invokedAgentId = resolveInvokedAgentId(options.agent, environment),
    configuredAgentOverrideId = options.agentOverride,
    dbPathOverride = dbOverride,
    repoRoot = options.repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
    includeDiffStat = options.diff.includeDiffStat,
    selectedDiffHunkPaths = options.diff.selectedDiffHunkPaths,
    selectedDiffMaxHunks = options.diff.selectedDiffMaxHunks,
    selectedDiffMaxLines = options.diff.selectedDiffMaxLines,
    selectedDiffMaxBytes = options.diff.selectedDiffMaxBytes,
  )

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
  linkedMapOf<String, Any?>(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep,
    "active_agent" to it.activeAgent,
    "latest_liveness_signal" to it.latestLivenessSignal,
  ).apply {
    it.latestObservabilityEvent?.let { event -> put("latest_observability_event", event) }
    it.requestedDiffStat?.let { stat -> put("diff_stat", stat.toGoalDiffStatCliMap()) }
    it.selectedDiffHunks?.let { hunks -> put("selected_diff_hunks", hunks.toGoalSelectedDiffHunksCliMap()) }
  }
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
  (payload["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "latest_observability: phase=${event["workflow_phase"]} role=${event["worker_role"]} " +
        "liveness=${event["liveness_class"]} sequence=${event["sequence_number"]}",
    )
  }
  appendDiffStatusLines(payload)
}

private fun Map<String, Any?>.goalStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

private fun Map<String, Any?>.withWatchRefresh(refreshIndex: Int): Map<String, Any?> =
  linkedMapOf<String, Any?>("refresh_index" to refreshIndex).apply { putAll(this@withWatchRefresh) }

private fun goalWatchText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("refresh_count: ${payload["refresh_count"]}")
  appendLine("interval_seconds: ${payload["interval_seconds"]}")
  val latestRefresh = payload["latest_refresh"] as? Map<*, *> ?: return@buildString
  append(goalWatchRefreshText(latestRefresh))
}

private fun goalWatchRefreshText(refresh: Map<*, *>): String = buildString {
  appendLine(
    "watch_refresh: index=${refresh["refresh_index"]} status=${refresh["status"]} " +
      "current_subtask=${refresh["current_subtask"] ?: "none"} " +
      "current_step=${refresh["current_step"] ?: "none"} " +
      "liveness=${refresh["latest_liveness_signal"] ?: "none"}",
  )
  (refresh["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "watch_observability: index=${refresh["refresh_index"]} phase=${event["workflow_phase"]} " +
        "role=${event["worker_role"]} liveness=${event["liveness_class"]} " +
        "sequence=${event["sequence_number"]}",
    )
  }
  appendDiffStatusLines(refresh, watchIndex = refresh["refresh_index"]?.toString())
}

private fun StringBuilder.appendDiffStatusLines(payload: Map<*, *>, watchIndex: String? = null) {
  val indexPrefix = watchIndex?.let { " index=$it" }.orEmpty()
  (payload["diff_stat"] as? Map<*, *>)?.let { stat ->
    appendLine(
      "${if (watchIndex == null) "diff_stat" else "watch_diff_stat"}:$indexPrefix " +
        "files_changed=${stat["files_changed"]} insertions=${stat["insertions"]} deletions=${stat["deletions"]}",
    )
  }
  val selected = payload["selected_diff_hunks"] as? Map<*, *> ?: return
  val hunks = (selected["hunks"] as? List<*>).orEmpty()
  appendLine(
    "${if (watchIndex == null) "selected_diff_hunks" else "watch_selected_diff_hunks"}:$indexPrefix " +
      "count=${hunks.size} truncated=${selected["truncated"]}",
  )
  hunks.forEachIndexed { hunkIndex, rawHunk ->
    val hunk = rawHunk as? Map<*, *> ?: return@forEachIndexed
    val path = hunk["path"].toString().goalCliToken()
    val staged = hunk["staged"]
    val lines = (hunk["lines"] as? List<*>).orEmpty()
    appendLine(
      "${if (watchIndex == null) "selected_diff_hunk" else "watch_selected_diff_hunk"}:$indexPrefix " +
        "hunk_index=${hunkIndex + 1} path=$path staged=$staged " +
        "header=${hunk["header"].toString().goalCliToken()} line_count=${lines.size} truncated=${hunk["truncated"]}",
    )
    lines.forEachIndexed { lineIndex, rawLine ->
      appendLine(
        "${if (watchIndex == null) "selected_diff_line" else "watch_selected_diff_line"}:$indexPrefix " +
          "hunk_index=${hunkIndex + 1} line_index=${lineIndex + 1} path=$path staged=$staged " +
          "text=${rawLine.toString().goalCliToken()}",
      )
    }
  }
}

private fun String.goalCliToken(): String = replace("\\", "\\\\")
  .replace("\t", "\\t")
  .replace(" ", "\\s")

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

// SKILL-64 Subtask 3 (AC18): resolve the child invoking-agent id without a
// silent hardcoded codex fallback. Explicit --agent wins, then the
// SKILL_BILL_AGENT env var, then best-effort detection of the invoking agent's
// execution context, and only then the documented last-resort default below.
// --agent-override is independent and continues to win at the
// AgentRunService.effectiveAgent seam; this only sources invokedAgentId.
private fun resolveInvokedAgentId(explicitAgent: String?, environment: Map<String, String>): String =
  explicitAgent?.takeIf(String::isNotBlank)
    ?: environment["SKILL_BILL_AGENT"]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(environment)?.id
    ?: DEFAULT_GOAL_AGENT

// Documented last-resort default used only when no explicit flag, env, or
// detected invoking-agent context is available.
private const val DEFAULT_GOAL_AGENT = "codex"
private const val GOAL_LIVENESS_DURABLE_PROGRESS = "durable_progress"
private const val GOAL_LIVENESS_FILE_ACTIVITY = "file_activity"
private const val GOAL_LIVENESS_OUTPUT_ONLY = "output_only"
private const val GOAL_LIVENESS_IDLE = "idle"
private const val DEFAULT_GOAL_WATCH_INTERVAL_SECONDS = 5
private const val DEFAULT_GOAL_WATCH_REFRESHES = 1
private const val MILLIS_PER_SECOND = 1_000L
private const val RUNTIME_EXECUTABLE_ENV = "SKILL_BILL_RUNTIME_EXECUTABLE"
private const val RUNTIME_CLASSPATH_ENV = "SKILL_BILL_RUNTIME_CLASSPATH"
private const val RUNTIME_PATH_SEPARATOR_ENV = "SKILL_BILL_PATH_SEPARATOR"
private val GOAL_SUBTASK_REGEX = Regex("""\bsubtask\s+(\d+)\b""")
private val GOAL_STEP_REGEX = Regex("""\bstep\s+([a-zA-Z0-9_-]+)\b""")
