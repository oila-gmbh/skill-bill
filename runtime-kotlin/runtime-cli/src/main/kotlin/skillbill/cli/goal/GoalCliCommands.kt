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
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.UnaddressedFindingsLedgerService
import skillbill.application.model.DEFAULT_GOAL_PLANNING_BUDGET
import skillbill.application.model.GoalRunnerAcceptRequest
import skillbill.application.model.GoalRunnerAcceptResult
import skillbill.application.model.GoalRunnerPauseResult
import skillbill.application.model.GoalRunnerRepairRequest
import skillbill.application.model.GoalRunnerRepairResult
import skillbill.application.model.GoalRunnerRepairStatus
import skillbill.application.model.GoalRunnerReplanRequest
import skillbill.application.model.GoalRunnerReplanResult
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerResumeResult
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.model.GoalRunnerStopStatus
import skillbill.application.model.GoalRunnerStopVerbResult
import skillbill.application.review.RequestedReviewMode
import skillbill.application.system.RuntimeProvenanceService
import skillbill.application.telemetry.TelemetryService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.refuseUnavailableAgentLaunchers
import skillbill.cli.featuretask.parseAgentAddonSelection
import skillbill.cli.telemetry.drainTelemetryOnCompletion
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.error.DatabaseAccessError
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalControlSubcommands(
  val pause: GoalPauseCommand,
  val stop: GoalStopCommand,
  val resume: GoalResumeCommand,
  val reset: GoalResetCommand,
  val replan: GoalReplanCommand,
  val accept: GoalAcceptCommand,
  val repair: GoalRepairCommand,
)

@Inject
class GoalRunSubcommands(
  val status: GoalStatusCommand,
  val watch: GoalWatchCommand,
  val controls: GoalControlSubcommands,
  val findings: GoalFindingsCommand,
)

@Inject
@Suppress("LongParameterList")
class GoalRunCommand(
  private val goalRunner: GoalRunner,
  private val runtimeProvenanceService: RuntimeProvenanceService,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val executableLookup: ExecutableLookup,
  goalRunSubcommands: GoalRunSubcommands,
  private val telemetryService: TelemetryService,
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
    help = "Review execution mode for every child: inline (default, one review subagent per " +
      "pass), auto (also resolves inline on every pass), or delegated (experimental " +
      "specialist fan-out, explicit only).",
  )
  private val parallelReviewAgent by option(
    "--parallel-review-agent",
    help =
    "Run every child review with a second parallel agent lane. " +
      "Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
  )
  private val agentAddonSelectionJson by option(
    "--agent-addon-selection-json",
    help = "Already-resolved ordered agent add-on selection JSON. Raw agent-addon tokens are not accepted here.",
  )
  private val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Optional per-subtask wall-clock cap in minutes. Default is no wall-clock cap.",
  ).int()
  private val progressIdleTimeoutMinutes by option(
    "--progress-idle-timeout-minutes",
    help = "Per-subtask durable workflow-progress idle timeout in minutes (default " +
      "$DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES). A subtask with no durable progress and no " +
      "file activity for this long is killed; active work is spared. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES)
  private val planningBudgetMinutes by option(
    "--planning-budget-minutes",
    help = "Per-plan wall-clock budget for goal planning in minutes (default " +
      "${DEFAULT_GOAL_PLANNING_BUDGET.inWholeMinutes}). Planning writes no durable progress, so it is " +
      "bounded by this budget rather than the progress-idle timeout. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_PLANNING_BUDGET.inWholeMinutes.toInt())
  private val stopAfterSubtask by option(
    "--stop-after-subtask",
    help = "Pause the parent after this positive subtask ID reaches durable terminal success.",
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
    subcommands(
      goalRunSubcommands.status,
      goalRunSubcommands.watch,
      goalRunSubcommands.controls.pause,
      goalRunSubcommands.controls.stop,
      goalRunSubcommands.controls.resume,
      goalRunSubcommands.controls.reset,
      goalRunSubcommands.controls.replan,
      goalRunSubcommands.controls.accept,
      goalRunSubcommands.controls.repair,
      goalRunSubcommands.findings,
    )
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val candidateAgentIds = listOf(
      resolveInvokedAgentId(agent, state.environment),
      agentOverride,
      parallelReviewAgent?.takeIf(String::isNotBlank),
    )
    // An agent whose headless CLI is absent would otherwise spawn-fail at goal planning, after the
    // goal record already exists and is blocked at subtask 0.
    refuseUnavailableAgentLaunchers(candidateAgentIds, executableLookup)
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for goal run.")
    if (stopAfterSubtask != null && requireNotNull(stopAfterSubtask) <= 0) {
      throw UsageError("--stop-after-subtask must be a positive integer.")
    }
    val invokedAgentId = resolveInvokedAgentId(agent, state.environment)
    val receivingAgents = listOfNotNull(
      invokedAgentId,
      agentOverride?.takeIf(String::isNotBlank),
      parallelReviewAgent?.takeIf(String::isNotBlank),
    ).distinct()
    val persistedSelection = parseAgentAddonSelection(agentAddonSelectionJson)
    val hydratedSelection = if (persistedSelection.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      agentAddonSelectionPort.verifyPersisted(
        persistedSelection,
        AgentAddonConsumer.BILL_FEATURE,
        receivingAgents,
      )
    }
    val effectiveRepoRoot = repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
      ?: Path.of("").toAbsolutePath().normalize()
    val presenter = GoalRunPresenter(
      issueKey = runIssueKey,
      state = state,
      liveOutput = !noLiveOutput,
      repoRoot = effectiveRepoRoot,
      dbOverride = state.dbOverride,
      runtimeProvenance = runtimeProvenanceService.current(
        executablePathHint = state.environment[RUNTIME_EXECUTABLE_ENV],
        classPath = state.environment[RUNTIME_CLASSPATH_ENV] ?: System.getProperty("java.class.path").orEmpty(),
        javaCommand = ProcessHandle.current().info().command().orElse(null),
        pathSeparator = state.environment[RUNTIME_PATH_SEPARATOR_ENV] ?: System.getProperty("path.separator", ":"),
      ),
    )
    presenter.emitStartupProvenance()
    val report = goalRunner.run(
      runRequest(runIssueKey, invokedAgentId, hydratedSelection, presenter, effectiveRepoRoot),
    )
    val payload = report.toGoalRunCliMap()
    state.completeText(goalRunText(payload), payload, exitCode = payload.goalExitCode())
    // Parent completion only: child CLI feature-task processes drain themselves, so a per-child
    // parent drain would only add concurrent SQLite writers on the same database.
    drainTelemetryOnCompletion(telemetryService, state.dbOverride)
  }

  private fun runRequest(
    runIssueKey: String,
    invokedAgentId: String,
    hydratedSelection: HydratedAgentAddonSelection,
    presenter: GoalRunPresenter,
    effectiveRepoRoot: Path,
  ): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = runIssueKey,
    repoRoot = effectiveRepoRoot,
    invokedAgentId = invokedAgentId,
    configuredAgentOverrideId = agentOverride,
    dbPathOverride = state.dbOverride,
    timeout = maxWallClockMinutes?.minutes,
    progressIdleTimeout = progressIdleTimeoutMinutes.takeIf { it > 0 }?.minutes,
    planningBudget = planningBudgetMinutes.takeIf { it > 0 }?.minutes,
    outputSink = presenter.outputSink(includeRawChildOutput = debugChildOutput),
    eventSink = presenter.eventSink(),
    codeReviewMode = parseCodeReviewMode(codeReviewMode),
    parallelReviewAgent = parallelReviewAgent?.takeIf(String::isNotBlank),
    agentAddonSelection = hydratedSelection,
    stopAfterSubtaskId = stopAfterSubtask,
  )
}

private fun parseCodeReviewMode(raw: String?): CodeReviewExecutionMode? = raw?.let(RequestedReviewMode::parse)

@Inject
class GoalFindingsCommand(
  private val ledgerService: UnaddressedFindingsLedgerService,
  private val state: CliRunState,
) : DocumentedCliCommand("findings", "Show the goal-wide unaddressed-findings ledger.") {
  private val issueKey by option("--issue-key", help = "Parent issue key.").required()

  override fun run() {
    val ledger = ledgerService.ledger(issueKey, state.dbOverride)
    val payload = linkedMapOf<String, Any?>(
      "issue_key" to ledger.issueKey,
      "unaddressed_findings" to ledger.findings.size,
      "severity_breakdown" to ledger.severityBreakdown,
      "findings" to ledger.findings.map { finding ->
        linkedMapOf(
          "subtask_id" to finding.subtaskId,
          "workflow_id" to finding.workflowId,
          "review_pass_number" to finding.reviewPassNumber,
          "finding_ordinal" to finding.findingOrdinal,
          "severity" to finding.severity,
          "issue_category" to finding.issueCategory,
          "location" to finding.location,
          "summary" to finding.summary,
        )
      },
    )
    val text = buildString {
      appendLine("issue_key=${ledger.issueKey} unaddressed_findings=${ledger.findings.size}")
      ledger.findings.forEach { finding ->
        appendLine(
          "subtask=${finding.subtaskId} pass=${finding.reviewPassNumber} " +
            "severity=${finding.severity} category=${finding.issueCategory} " +
            "location=${finding.location} ${finding.summary}",
        )
      }
    }
    state.completeText(text, payload)
  }
}

@Inject
class GoalStatusCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only decomposed goal status.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val monitorOnly by option(
    "--monitor",
    help = "Render one bounded read-only snapshot for bill-monitor; never launches or polls a goal.",
  ).flag(default = false)
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
    val options = statusCliRequestOptions()
    if (options.monitorOnly && !FeatureTaskExecutionIdentityPolicy.ISSUE_KEY_PATTERN.matches(options.issueKey)) {
      throw UsageError(
        "Monitor requires one supported issue key matching " +
          "${FeatureTaskExecutionIdentityPolicy.ISSUE_KEY_PATTERN.pattern}.",
      )
    }
    if (options.monitorOnly && (diffStat || diffHunks.isNotEmpty())) {
      throw UsageError("Monitor accepts only one bounded status snapshot; omit diff options.")
    }
    val projection = try {
      goalRunnerStatusService.status(state.goalStatusRequest(options))
    } catch (error: DatabaseAccessError) {
      if (!options.monitorOnly) throw error
      val payload = databaseUnavailableGoalStatusCliMap(issueKey, error)
      state.completeText(goalMonitorStatusText(payload), payload, exitCode = payload.goalStatusExitCode())
      return
    }
    val payload = if (options.monitorOnly) {
      projection.toBoundedGoalStatusCliMap(issueKey)
    } else {
      projection.toGoalStatusCliMap(issueKey)
    }
    val text = if (options.monitorOnly) goalMonitorStatusText(payload) else goalStatusText(payload)
    state.completeText(text, payload, exitCode = payload.goalStatusExitCode())
  }

  private fun statusCliRequestOptions(): GoalStatusCliRequestOptions = GoalStatusCliRequestOptions(
    issueKey = issueKey,
    monitorOnly = monitorOnly,
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
class GoalPauseCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("pause", "Request a durable pause for an already-running goal.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.pause(
      issueKey,
      state.dbOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: Path.of("").toAbsolutePath().normalize(),
    )
    val payload = result.toGoalPauseCliMap()
    state.completeText(goalPauseText(payload), payload, exitCode = payload.goalPauseExitCode())
  }
}

@Inject
class GoalStopCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("stop", "Stop a running goal now: record the operator stop, then terminate the runner.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.stop(
      issueKey,
      state.dbOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: Path.of("").toAbsolutePath().normalize(),
    )
    val payload = result.toGoalStopCliMap()
    state.completeText(goalStopText(payload), payload, exitCode = payload.goalStopExitCode())
  }
}

@Inject
class GoalResumeCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("resume", "Clear a durable pause for a goal without starting child runs.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.resume(
      issueKey,
      state.dbOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: Path.of("").toAbsolutePath().normalize(),
    )
    val payload = result.toGoalResumeCliMap()
    state.completeText(goalResumeText(payload), payload, exitCode = payload.goalPauseExitCode())
  }
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
    help = "Stop after this many refreshes. Zero follows until the goal finishes and is the default.",
  ).int().default(DEFAULT_GOAL_WATCH_REFRESHES)
  private val showUnchanged by option(
    "--show-unchanged",
    help = "Compatibility option. Every refresh, including unchanged output, is shown by default.",
  ).flag(default = false)
  private val suppressUnchanged by option(
    "--suppress-unchanged",
    help = "Print only changed refreshes. The first and loop-ending refresh are always shown.",
  ).flag(default = false)

  override fun run() {
    require(intervalSeconds >= 0) { "--interval-seconds must be non-negative." }
    require(maxRefreshes >= 0) { "--max-refreshes must be non-negative." }
    var latestRefresh: Map<String, Any?>? = null
    var refreshCount = 0
    var stopReason = ""
    var lastPrintedRefresh: String? = null
    var consecutiveIdleRefreshes = 0
    while (true) {
      refreshCount += 1
      val projection = goalRunnerStatusService.statusRefresh(
        state.goalStatusRequest(statusCliRequestOptions()),
      )
      val refresh = projection.toGoalStatusCliMap(issueKey).withWatchRefresh(refreshCount)
      latestRefresh = refresh
      consecutiveIdleRefreshes = if (projection?.executionLiveness == ExecutionLiveness.IDLE) {
        consecutiveIdleRefreshes + 1
      } else {
        0
      }
      stopReason = refresh.goalWatchStopReason(
        refreshCount = refreshCount,
        maxRefreshes = maxRefreshes,
        idleStop = consecutiveIdleRefreshes >= IDLE_STOP_CONSECUTIVE_REFRESHES,
      ) ?: ""
      val renderedRefresh = goalWatchRefreshText(refresh)
      val normalizedRefresh = goalWatchRefreshText(
        refresh.toMutableMap().apply { this["refresh_index"] = "<refresh_index>" },
      )
      val endsLoop = stopReason.isNotEmpty()
      val refreshChanged = lastPrintedRefresh == null || normalizedRefresh != lastPrintedRefresh
      val shouldPrintRefresh = endsLoop || showUnchanged || !suppressUnchanged || refreshChanged
      if (shouldPrintRefresh) {
        state.liveStdout(renderedRefresh)
        lastPrintedRefresh = normalizedRefresh
      }
      if (endsLoop) {
        break
      }
      if (intervalSeconds > 0) {
        Thread.sleep(intervalSeconds * MILLIS_PER_SECOND)
      }
    }
    val payload = linkedMapOf<String, Any?>(
      "status" to latestRefresh.get("status"),
      "issue_key" to issueKey,
      "refresh_count" to refreshCount,
      "interval_seconds" to intervalSeconds,
      "latest_refresh" to latestRefresh,
      "stop_reason" to stopReason,
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
  private val preservePlanning by option(
    "--preserve-planning",
    help = "Delete incompatible child workflows while preserving immutable goal planning checkpoints.",
  ).flag(default = false)
  private val subtaskId by option(
    "--subtask",
    help = "Selected subtask ID for scoped incompatible-child recovery.",
  ).int()
  private val deleteChildWorkflow by option(
    "--delete-child-workflow",
    help = "Explicitly delete the selected subtask's incompatible terminal child workflow.",
  ).flag(default = false)
  private val confirmIssueKey by option(
    "--confirm-issue-key",
    help = "Confirmation gate for --hard. Must match the issue key.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")

  override fun run() {
    if ((subtaskId != null) != deleteChildWorkflow) {
      throw UsageError("--subtask ID and --delete-child-workflow must be supplied together.")
    }
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    if (deleteChildWorkflow && (hard || preservePlanning)) {
      throw UsageError(
        "--subtask ID --delete-child-workflow is incompatible with --hard and --preserve-planning.",
      )
    }
    if (preservePlanning && !hard) {
      throw UsageError(
        "--preserve-planning only applies to a hard reset; a soft reset never deletes child workflows. " +
          "Pass --hard as well, or drop --preserve-planning.",
      )
    }
    if (hard && !force && confirmIssueKey != issueKey) {
      throw UsageError(
        "Hard reset requires explicit confirmation. Pass --confirm-issue-key $issueKey or --force.",
      )
    }
    emitHardResetAcceptanceWarning()
    val result = goalRunnerStatusService.reset(
      GoalRunnerResetRequest(
        issueKey = issueKey,
        hard = hard,
        preservePlanning = preservePlanning,
        subtaskId = subtaskId,
        deleteChildWorkflow = deleteChildWorkflow,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
      ),
    )
    val payload = result.toGoalResetCliMap(issueKey, hard)
    state.completeText(goalResetText(payload), payload, exitCode = payload.goalResetExitCode())
  }

  private fun emitHardResetAcceptanceWarning() {
    if (!hard) return
    val discardedAcceptances = goalRunnerStatusService.hardResetPreflight(issueKey, state.dbOverride)
    if (discardedAcceptances.isNotEmpty()) {
      state.liveStdout(hardResetAcceptanceWarning(issueKey, discardedAcceptances))
    }
  }
}

@Inject
class GoalReplanCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "replan",
  "Discard one subtask plan while preserving sibling plans, shared preplan, and runtime state; " +
    "pass --include-shared-preplan to also discard the shared preplan and every sibling plan.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Subtask whose stored plan should be discarded and regenerated on the next goal run.",
  ).int().required()
  private val includeSharedPreplan by option(
    "--include-shared-preplan",
    help = "Also discard the goal-wide shared preplan and every sibling subtask plan " +
      "(planning rows only; runtime state is untouched).",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")

  override fun run() {
    if (subtaskId <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result = goalRunnerStatusService.replan(
      GoalRunnerReplanRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
        includeSharedPreplan = includeSharedPreplan,
      ),
    )
    val payload = result.toGoalReplanCliMap(issueKey)
    state.completeText(goalReplanText(payload), payload, exitCode = payload.goalResetExitCode())
  }
}

@Inject
class GoalAcceptCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "accept",
  "Record that a subtask's work landed outside the runtime, so the goal advances past it.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option("--subtask", help = "Subtask id whose work already landed.")
    .int()
    .required()
  private val commit by option("--commit", help = "Commit that carries the landed work.").required()
  private val reason by option("--reason", help = "Why this subtask was completed outside the runtime.").required()
  private val repoRoot by option("--repo-root", help = "Repository root used to verify the commit.")
  private val restoreAfterHardReset by option(
    "--restore-after-hard-reset",
    help = "Restore an acceptance discarded by a goal-wide hard reset.",
  ).flag(default = false)

  override fun run() {
    val result = goalRunnerStatusService.accept(
      GoalRunnerAcceptRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        commitSha = commit,
        reason = reason,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
        restoreAfterHardReset = restoreAfterHardReset,
      ),
    )
    val payload = result.toGoalAcceptCliMap()
    state.completeText(goalAcceptText(payload), payload, exitCode = payload.goalResetExitCode())
  }
}

@Inject
class GoalRepairCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "repair",
  "Inspect or clear known goal-child resume wedges without discarding completed work. " +
    "Default is inspect-only; pass --apply to act. " +
    "Clears: missing validation_depth on the continuation artifact; unreachable stored " +
    "review_base_sha; unreachable stored remediation_base_sha; stale blocked " +
    "goal_continuation_outcome. Does not touch: completed commit shas, review pass history, " +
    "audit repair state, planning checkpoints, or anything goal reset/replan/accept own.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Optional subtask ID to scope diagnosis and repair to one child.",
  ).int()
  private val apply by option(
    "--apply",
    help = "Apply repairs for diagnosed wedges. Without this flag the command only reports.",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for reachability checks.")

  override fun run() {
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result = goalRunnerStatusService.repair(
      GoalRunnerRepairRequest(
        issueKey = issueKey,
        apply = apply,
        subtaskId = subtaskId,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
      ),
    )
    val payload = result.toGoalRepairCliMap()
    state.completeText(goalRepairText(payload), payload, exitCode = payload.goalRepairExitCode())
  }
}

private class GoalRunPresenter(
  private val issueKey: String,
  private val state: CliRunState,
  private val liveOutput: Boolean,
  private val repoRoot: Path,
  private val dbOverride: String?,
  private val runtimeProvenance: RuntimeProvenanceContract,
) {
  fun emitStartupProvenance() {
    val commandPrefix = buildString {
      append("skill-bill")
      dbOverride?.let { append(" --db ").append(shellQuote(it)) }
    }
    val rootArgument = "--repo-root ${shellQuote(repoRoot.toString())}"
    state.liveStdout(
      "goal $issueKey: launched runtime executable=${runtimeProvenance.executablePath} " +
        "version=${runtimeProvenance.version} build_id=${runtimeProvenance.buildId}\n" +
        "monitor (read-only; mutates nothing; no model tokens):\n" +
        "$commandPrefix goal watch $issueKey $rootArgument --interval-seconds 5\n" +
        "$commandPrefix goal status $issueKey $rootArgument --diff-stat\n",
    )
  }

  fun eventSink(): skillbill.application.model.GoalRunnerEventSink = skillbill.application.model.GoalRunnerEventSink { }

  fun outputSink(includeRawChildOutput: Boolean): AgentRunOutputSink = if (!liveOutput) {
    AgentRunOutputSink.NONE
  } else {
    AgentRunOutputSink { stream, text ->
      if (includeRawChildOutput) {
        when (stream) {
          AgentRunOutputStream.STDOUT -> state.liveStdout(text)
          AgentRunOutputStream.STDERR -> state.liveStderr(text)
        }
      }
    }
  }
}

private fun GoalRunnerRunReport.toGoalRunCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "feature_name" to featureName,
    "attempted_subtasks" to attemptedSubtasks,
    "subtasks_completed" to subtasksCompleted,
    "subtasks_pending" to subtasksPending,
    "subtasks_blocked" to subtasksBlocked,
    "unaddressed_findings" to unaddressedFindingCount,
    "unaddressed_severity_breakdown" to unaddressedSeverityBreakdown,
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

private fun GoalRunnerPauseResult.toGoalPauseCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "paused" to paused,
  "pause_requested" to pauseRequested,
  "pause_reason" to pauseReason,
)

private fun goalPauseText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["pause_reason"]?.let { appendLine("reason: $it") }
}

private fun GoalRunnerStopVerbResult.toGoalStopCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status.wireValue,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "pause_reason" to pauseReason,
  "paused_at" to pausedAt,
  "termination_attempted" to terminationAttempted,
)

private fun goalStopText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["pause_reason"]?.let { appendLine("reason: $it") }
  payload["paused_at"]?.let { appendLine("paused at: $it") }
}

private fun GoalRunnerResumeResult.toGoalResumeCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "paused" to false,
  "pause_requested" to false,
  "cleared_pause_reason" to clearedPauseReason,
)

private fun goalResumeText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["cleared_pause_reason"]?.let { appendLine("cleared reason: $it") }
}

private data class GoalStatusCliRequestOptions(
  val issueKey: String,
  val monitorOnly: Boolean = false,
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
    repoRoot = options.repoRoot?.let(Path::of)
      ?.let { root -> if (options.monitorOnly) canonicalRepositoryRoot(root) else root.toAbsolutePath().normalize() }
      ?: if (options.monitorOnly) canonicalRepositoryRoot(Path.of("")) else Path.of("").toAbsolutePath().normalize(),
    includeDiffStat = options.diff.includeDiffStat,
    selectedDiffHunkPaths = options.diff.selectedDiffHunkPaths,
    selectedDiffMaxHunks = options.diff.selectedDiffMaxHunks,
    selectedDiffMaxLines = options.diff.selectedDiffMaxLines,
    selectedDiffMaxBytes = options.diff.selectedDiffMaxBytes,
  )

private fun goalRunText(payload: Map<String, Any?>): String = when (payload["status"]) {
  "complete" -> buildString {
    appendLine("goal ${payload["issue_key"]}: finished")
    append("summary: ")
    append(singleLineBounded(payload["feature_name"]?.toString().orEmpty().ifBlank { "goal" }))
    append(" — ")
    val completedCount = (payload["subtasks_completed"] as? Number)?.toInt() ?: 0
    val pendingCount = (payload["subtasks_pending"] as? Number)?.toInt() ?: 0
    val blockedCount = (payload["subtasks_blocked"] as? Number)?.toInt() ?: 0
    val totalCount = completedCount + pendingCount + blockedCount
    append(completedCount)
    append("/")
    append(totalCount)
    append(" subtasks complete; pending=")
    append(pendingCount)
    append("; blocked=")
    append(blockedCount)
    payload["pull_request_url"]?.toString()?.takeIf(String::isNotBlank)?.let { url ->
      append("; PR ")
      append(singleLineBounded(url))
    }
    appendLine()
  }
  else -> buildString {
    val reason = payload["reason"]?.toString()?.lowercase().orEmpty()
    val verb = when {
      reason == "paused" -> "paused"
      reason.contains("failed") || reason.contains("timeout") -> "failed"
      else -> "blocked"
    }
    append("goal ${payload["issue_key"]}: $verb")
    payload["subtask_id"]?.let { append(" at subtask $it") }
    append(" — ")
    append(singleLineBounded(payload["blocked_reason"]?.toString() ?: reason.ifBlank { "terminal outcome" }))
    appendLine()
  }
}

private fun singleLineBounded(value: String, limit: Int = MAX_TERMINAL_FIELD_CHARS): String =
  value.replace(Regex("\\s+"), " ").trim().take(limit)

private const val MAX_TERMINAL_FIELD_CHARS = 240

private const val GOAL_STATUS_DATABASE_UNAVAILABLE = "database_unavailable"

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

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
    "execution_liveness" to it.executionLiveness.wireValue,
    "latest_liveness_signal" to it.latestLivenessSignal,
    "paused" to it.paused,
    "pause_requested" to it.pauseRequested,
    "pause_reason" to it.pauseReason,
    "stop_after_subtask" to it.stopAfterSubtaskId,
  ).apply {
    it.planning?.let { planning ->
      put(
        "planning",
        linkedMapOf(
          "state" to planning.state.wireValue,
          "shared_preplan_prepared" to planning.sharedPreplanPrepared,
          "planned_subtask_count" to planning.plannedSubtaskCount,
          "total_subtask_count" to planning.totalSubtaskCount,
          "current_planning_subtask" to planning.currentPlanningSubtaskId,
          "reason" to planning.reason,
        ),
      )
    }
    it.latestObservabilityEvent?.let { event -> put("latest_observability_event", event) }
    it.requestedDiffStat?.let { stat -> put("diff_stat", stat.toGoalDiffStatCliMap()) }
    it.selectedDiffHunks?.let { hunks -> put("selected_diff_hunks", hunks.toGoalSelectedDiffHunksCliMap()) }
    putGoalLedgerCliEntries(it)
    it.outOfBandAcceptances.toGoalAcceptanceCliList()?.let { list -> put("out_of_band_acceptances", list) }
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
  "execution_liveness" to ExecutionLiveness.UNKNOWN.wireValue,
  "latest_liveness_signal" to null,
  "paused" to false,
  "pause_requested" to false,
  "pause_reason" to null,
  "stop_after_subtask" to null,
)

private fun GoalRunnerStatusProjection?.toBoundedGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep?.let(::singleLineBounded),
    "execution_liveness" to it.executionLiveness.wireValue,
    "resumable_state" to it.monitorResumableState(),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to singleLineBounded(issueKey),
  "resumable_state" to "not_found",
)

private fun databaseUnavailableGoalStatusCliMap(issueKey: String, error: DatabaseAccessError): Map<String, Any?> =
  linkedMapOf(
    "status" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "issue_key" to singleLineBounded(issueKey),
    "resumable_state" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "reason" to singleLineBounded(error.condition),
  )

private fun GoalRunnerStatusProjection.monitorResumableState(): String = currentStep.let { step ->
  when {
    paused -> "paused"
    pauseRequested -> "pause_requested"
    currentSubtaskId == null && pendingCount == 0 && blockedCount == 0 -> "complete"
    step.isNullOrBlank() -> "resumable"
    else -> "resumable_at:${singleLineBounded(step)}"
  }
}

private fun MutableMap<String, Any?>.putGoalLedgerCliEntries(projection: GoalRunnerStatusProjection) {
  if (projection.blockedAttemptCount > 0) put("blocked_attempt_count", projection.blockedAttemptCount)
  if (projection.supervisorKillCount > 0) put("supervisor_kill_count", projection.supervisorKillCount)
  if (projection.phaseAttemptCounts.isNotEmpty()) put("phase_attempt_counts", projection.phaseAttemptCounts)
  if (projection.cumulativeFixIterations.isNotEmpty()) {
    put("cumulative_fix_iterations", projection.cumulativeFixIterations)
  }
  if (projection.reAttemptCauseCounts.isNotEmpty()) put("re_attempt_causes", projection.reAttemptCauseCounts)
  projection.findingsInScope?.let { count -> put("findings_in_scope", count) }
}

private fun List<GoalRunnerAcceptedSubtask>.toGoalAcceptanceCliList(): List<Map<String, Any?>>? = takeIf {
  it.isNotEmpty()
}?.map { acceptance ->
  linkedMapOf(
    "subtask_id" to acceptance.subtaskId,
    "commit_sha" to acceptance.commitSha,
    "reason" to acceptance.reason,
    "accepted_at" to acceptance.acceptedAt,
  )
}

private fun goalStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
  appendLine("current_step: ${payload["current_step"] ?: "none"}")
  appendLine("active_agent: ${payload["active_agent"] ?: "none"}")
  appendLine("execution_liveness: ${payload["execution_liveness"]}")
  appendLine("latest_liveness_signal: ${payload["latest_liveness_signal"] ?: "none"}")
  appendLine("paused: ${payload["paused"]}")
  appendLine("pause_requested: ${payload["pause_requested"]}")
  appendLine("pause_reason: ${payload["pause_reason"] ?: "none"}")
  appendLine("stop_after_subtask: ${payload["stop_after_subtask"] ?: "none"}")
  (payload["planning"] as? Map<*, *>)?.let { planning ->
    appendLine(
      "planning: state=${planning["state"]} shared_preplan=${planning["shared_preplan_prepared"]} " +
        "planned=${planning["planned_subtask_count"]}/${planning["total_subtask_count"]} " +
        "current=${planning["current_planning_subtask"] ?: "none"}",
    )
    planning["reason"]?.let { appendLine("planning_reason: $it") }
  }
  (payload["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "latest_observability: phase=${event["workflow_phase"]} role=${event["worker_role"]} " +
        "liveness=${event["liveness_class"]} sequence=${event["sequence_number"]}",
    )
  }
  appendOperatorSurfaceLines(payload)
  appendDiffStatusLines(payload)
}

private fun goalMonitorStatusText(payload: Map<String, Any?>): String = if (payload["status"] == "not_found") {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: not_found")
    appendLine("resumable_state: not_found")
  }
} else if (payload["status"] == GOAL_STATUS_DATABASE_UNAVAILABLE) {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("resumable_state: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("reason: ${payload["reason"]}")
  }
} else {
  buildString {
    appendLine("complete: ${payload["complete_count"]}")
    appendLine("pending: ${payload["pending_count"]}")
    appendLine("blocked: ${payload["blocked_count"]}")
    appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
    appendLine("current_step: ${payload["current_step"] ?: "none"}")
    appendLine("execution_liveness: ${payload["execution_liveness"]}")
    appendLine("resumable_state: ${payload["resumable_state"]}")
  }
}

private fun Map<String, Any?>.goalStatusExitCode(): Int = if (!containsKey("status") || this["status"] == "ok") 0 else 1

private fun Map<String, Any?>.goalPauseExitCode(): Int = if (this["status"] != "not_found") 0 else 1

// Idempotent outcomes exit 0; a refused stop is a non-zero failure the operator must act on.
private fun Map<String, Any?>.goalStopExitCode(): Int = when (this["status"]) {
  GoalRunnerStopStatus.STOPPED.wireValue,
  GoalRunnerStopStatus.ALREADY_STOPPED.wireValue,
  GoalRunnerStopStatus.NO_LIVE_LEASE.wireValue,
  -> 0
  else -> 1
}

private fun Map<String, Any?>.withWatchRefresh(refreshIndex: Int): Map<String, Any?> =
  linkedMapOf<String, Any?>("refresh_index" to refreshIndex).apply { putAll(this@withWatchRefresh) }

private fun canonicalRepositoryRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}

private fun Map<String, Any?>.goalWatchStopReason(refreshCount: Int, maxRefreshes: Int, idleStop: Boolean): String? =
  when {
    this["status"] == "not_found" -> "not_found"
    // Only a reached pause is terminal. `pause_requested` is deferred to the next launch boundary, so
    // the current subtask keeps running; stopping on the request blinds the monitor for the rest of it.
    this["paused"] == true -> "goal_paused"
    (this["pending_count"] as? Number)?.toInt() == 0 -> "goal_terminal"
    idleStop -> "goal_idle"
    maxRefreshes > 0 && refreshCount >= maxRefreshes -> "max_refreshes"
    else -> null
  }

private fun goalWatchText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("refresh_count: ${payload["refresh_count"]}")
  appendLine("interval_seconds: ${payload["interval_seconds"]}")
  appendLine("stop_reason: ${payload["stop_reason"]}")
  val latestRefresh = payload["latest_refresh"] as? Map<*, *> ?: return@buildString
  append(goalWatchRefreshText(latestRefresh))
}

private fun goalWatchRefreshText(refresh: Map<*, *>): String = buildString {
  appendLine(
    "watch_refresh: index=${refresh["refresh_index"]} status=${refresh["status"]} " +
      "current_subtask=${refresh["current_subtask"] ?: "none"} " +
      "current_step=${refresh["current_step"] ?: "none"} " +
      "execution_liveness=${refresh["execution_liveness"] ?: "unknown"} " +
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

private fun StringBuilder.appendOperatorSurfaceLines(payload: Map<*, *>) {
  val blockedAttemptCount = (payload["blocked_attempt_count"] as? Number)?.toInt() ?: 0
  val supervisorKillCount = (payload["supervisor_kill_count"] as? Number)?.toInt() ?: 0
  if (blockedAttemptCount > 0 || supervisorKillCount > 0) {
    appendLine("blocked_attempts: $blockedAttemptCount supervisor_kills: $supervisorKillCount")
  }
  (payload["phase_attempt_counts"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { counts ->
    appendLine("phase_attempts: ${counts.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["cumulative_fix_iterations"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { iters ->
    appendLine("fix_iterations: ${iters.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["re_attempt_causes"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { causes ->
    appendLine("re_attempt_causes: ${causes.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["findings_in_scope"] as? Number)?.toInt()?.let { appendLine("findings_in_scope: $it") }
  (payload["out_of_band_acceptances"] as? List<*>)?.takeIf(List<*>::isNotEmpty)?.forEach { raw ->
    val acceptance = raw as? Map<*, *> ?: return@forEach
    appendLine(
      "accepted_out_of_band: subtask=${acceptance["subtask_id"]} commit=${acceptance["commit_sha"]} " +
        "at=${acceptance["accepted_at"]} reason=${acceptance["reason"]}",
    )
  }
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
    "status" to if (it.recovery?.recoveryCommand == null) "ok" else "recovery_required",
    "issue_key" to it.issueKey,
    "mode" to it.mode,
    "parent_workflow_id" to it.parentWorkflowId,
    "before" to resetSnapshotMap(it.before),
    "after" to resetSnapshotMap(it.after),
    "recovery" to it.recovery?.let { recovery ->
      linkedMapOf(
        "subtask_id" to recovery.subtaskId,
        "workflow_id" to recovery.workflowId,
        "classification" to recovery.classification,
        "command" to recovery.recoveryCommand,
      )
    },
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "mode" to if (hard) "hard" else "soft",
)

private fun GoalRunnerReplanResult?.toGoalReplanCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "mode" to "scoped_replan",
    "parent_workflow_id" to it.parentWorkflowId,
    "subtask_id" to it.subtaskId,
    "discarded_plan" to it.discardedPlan,
    "discarded_shared_preplan" to it.discardedSharedPreplan,
    "cascaded_plan_subtask_ids" to it.cascadedPlanSubtaskIds,
    "cleared_child_subtask_ids" to it.clearedChildSubtaskIds,
    "before" to replanSnapshotMap(it.before),
    "after" to replanSnapshotMap(it.after),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "mode" to "scoped_replan",
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

private fun replanSnapshotMap(snapshot: skillbill.application.model.GoalRunnerReplanSnapshot): Map<String, Any?> =
  linkedMapOf(
    "status" to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "shared_preplan_prepared" to snapshot.sharedPreplanPrepared,
    "planned_subtask_ids" to snapshot.plannedSubtaskIds,
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

private fun GoalRunnerAcceptResult.toGoalAcceptCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerAcceptResult.Accepted -> linkedMapOf(
    "status" to "ok",
    "issue_key" to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "subtask_id" to subtaskId,
    "commit_sha" to commitSha,
    "reason" to reason,
    "accepted_at" to acceptedAt,
    "after" to resetSnapshotMap(after),
  )
  is GoalRunnerAcceptResult.Rejected -> linkedMapOf(
    "status" to "rejected",
    "issue_key" to issueKey,
    "reason" to reason,
  )
}

private fun goalAcceptText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("reason: ${payload["reason"]}")
  payload["subtask_id"]?.let { appendLine("accepted_subtask: $it") }
  payload["commit_sha"]?.let { appendLine("commit_sha: $it") }
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  (payload["after"] as? Map<*, *>)?.let { after ->
    appendLine("after: status=${after["status"]}; current_subtask=${after["current_subtask"] ?: "none"}")
    appendLine("after_subtasks:")
    appendGoalResetSubtaskLines(this, after["subtasks"] as? List<*>)
  }
}

private fun hardResetAcceptanceWarning(issueKey: String, records: List<GoalRunnerAcceptedSubtask>): String =
  buildString {
    appendLine("hard_reset_acceptances_to_discard:")
    records.forEach { record ->
      val command = listOf(
        "skill-bill",
        "goal",
        "accept",
        issueKey,
        "--subtask",
        record.subtaskId.toString(),
        "--commit",
        record.commitSha,
        "--reason",
        record.reason,
        "--restore-after-hard-reset",
      ).joinToString(" ", transform = String::shellWord)
      appendLine(
        "acceptance: subtask=${record.subtaskId}; commit=${record.commitSha}; reason=${record.reason}",
      )
      appendLine("restore_command: $command")
    }
  }

private fun String.shellWord(): String = if (isNotEmpty() && all { it.isLetterOrDigit() || it in "-._/:@" }) {
  this
} else {
  "'${replace("'", "'\"'\"'")}'"
}

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
  (payload["recovery"] as? Map<*, *>)?.let { recovery ->
    appendLine(
      "recovery: subtask=${recovery["subtask_id"]}; workflow_id=${recovery["workflow_id"]}; " +
        "classification=${recovery["classification"]}",
    )
    recovery["command"]?.let { appendLine("recovery_command: $it") }
  }
}

private fun goalReplanText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("mode: ${payload["mode"]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  payload["subtask_id"]?.let { appendLine("discarded_plan: subtask=$it; existed=${payload["discarded_plan"]}") }
  val discardedShared = payload["discarded_shared_preplan"] as? Boolean == true
  val cascaded = (payload["cascaded_plan_subtask_ids"] as? List<*>).orEmpty().filterNotNull()
  if (discardedShared || cascaded.isNotEmpty()) {
    appendLine(
      "discarded_shared_preplan: $discardedShared; " +
        "cascaded_plans=${cascaded.joinToString(",").ifEmpty { "none" }}",
    )
  }
  val before = payload["before"] as? Map<*, *>
  val after = payload["after"] as? Map<*, *>
  if (before != null && after != null) {
    appendLine(
      "preserved: shared_preplan=${after["shared_preplan_prepared"]}; " +
        "planned_before=${(before["planned_subtask_ids"] as? List<*>)?.joinToString(",") ?: "none"}; " +
        "planned_after=${(after["planned_subtask_ids"] as? List<*>)?.joinToString(",") ?: "none"}",
    )
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
private const val DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES = 10
private const val DEFAULT_GOAL_WATCH_INTERVAL_SECONDS = 5
private const val DEFAULT_GOAL_WATCH_REFRESHES = 0
internal const val IDLE_STOP_CONSECUTIVE_REFRESHES = 3
private const val MILLIS_PER_SECOND = 1_000L
private const val RUNTIME_EXECUTABLE_ENV = "SKILL_BILL_RUNTIME_EXECUTABLE"
private const val RUNTIME_CLASSPATH_ENV = "SKILL_BILL_RUNTIME_CLASSPATH"
private const val RUNTIME_PATH_SEPARATOR_ENV = "SKILL_BILL_PATH_SEPARATOR"
