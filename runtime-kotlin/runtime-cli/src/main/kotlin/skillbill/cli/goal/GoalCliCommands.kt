package skillbill.cli.goal

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.goalrunner.model.DEFAULT_GOAL_PLANNING_BUDGET
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.invokingAgentResolutionHelp
import skillbill.cli.telemetry.drainTelemetryOnCompletion
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalControlFlowCommands(
  val pause: GoalPauseCommand,
  val stop: GoalStopCommand,
  val resume: GoalResumeCommand,
  val reset: GoalResetCommand,
)

@Inject
class GoalControlOperatorCommands(
  val replan: GoalReplanCommand,
  val accept: GoalAcceptCommand,
  val repair: GoalRepairCommand,
  val operatorDecision: GoalOperatorDecisionCommand,
)

@Inject
class GoalControlSubcommands(
  val flow: GoalControlFlowCommands,
  val operator: GoalControlOperatorCommands,
)

@Inject
class GoalRunSubcommands(
  val preflight: GoalPreflightCommand,
  val status: GoalStatusCommand,
  val watch: GoalWatchCommand,
  val controls: GoalControlSubcommands,
  val findings: GoalFindingsCommand,
  val planningLog: GoalPlanningLogCommand,
)

@Inject
class GoalRunCommand(
  private val deps: GoalRunDependencies,
  goalRunSubcommands: GoalRunSubcommands,
) : DocumentedCliCommand(
  "goal",
  "Run a decomposed goal in the foreground. Exit codes: complete=0, failed=1, paused=2, blocked=3.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.").optional()
  private val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Agent to use for child subtask runs instead of the invoking agent. Wins over --agent and detection.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for child agent runs.")
  private val codeReviewMode by option(
    "--code-review-mode",
    help = "Review execution mode for every child: inline (default, one review subagent per " +
      "pass) or auto (also resolves inline).",
  )
  private val agentAddonSelectionJson by option(
    "--agent-addon-selection-json",
    help = "Already-resolved ordered agent add-on selection JSON. Use --agent-addon for raw slugs.",
  )
  private val agentAddonSlugs by option(
    "--agent-addon",
    help = "Raw agent add-on slug. Repeat to preserve caller order; invalid values are rejected before launch.",
  ).multiple()
  private val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Per-subtask wall-clock cap in minutes (default " +
      "$DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES). Hard ceiling even when a child process is still " +
      "alive (progress-idle spares active work). Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES)
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
      goalRunSubcommands.preflight,
      goalRunSubcommands.status,
      goalRunSubcommands.watch,
      goalRunSubcommands.controls.flow.pause,
      goalRunSubcommands.controls.flow.stop,
      goalRunSubcommands.controls.flow.resume,
      goalRunSubcommands.controls.flow.reset,
      goalRunSubcommands.controls.operator.replan,
      goalRunSubcommands.controls.operator.accept,
      goalRunSubcommands.controls.operator.repair,
      goalRunSubcommands.controls.operator.operatorDecision,
      goalRunSubcommands.findings,
      goalRunSubcommands.planningLog,
    )
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val effectiveRepoRoot = repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
      ?: deps.inputs.repositoryRoot
    val invokedAgentId = resolveInvokedAgentId(agent, deps.inputs.environment)
    validateGoalRunInputs(
      GoalRunInputValidationArgs(
        issueKey = issueKey,
        stopAfterSubtask = stopAfterSubtask,
        agentAddonSlugs = agentAddonSlugs,
        agentAddonSelectionJson = agentAddonSelectionJson,
        agent = agent,
        agentOverride = agentOverride,
        inputs = deps.inputs,
        executableLookup = deps.executableLookup,
      ),
    )
    val runIssueKey = issueKey!!
    val receivingAgents = listOfNotNull(
      invokedAgentId,
      agentOverride?.takeIf(String::isNotBlank),
    ).distinct()
    val hydratedSelection = hydrateGoalRunAgentAddonSelection(
      GoalRunAgentAddonHydrationArgs(
        agentAddonSlugs = agentAddonSlugs,
        agentAddonSelectionJson = agentAddonSelectionJson,
        receivingAgents = receivingAgents,
        effectiveRepoRoot = effectiveRepoRoot,
        inputs = deps.inputs,
        agentAddonSelectionPort = deps.agentAddonSelectionPort,
        externalAgentAddonSourceConfigPort = deps.externalAgentAddonSourceConfigPort,
      ),
    )
    val presenter = GoalRunPresenter(
      issueKey = runIssueKey,
      inputs = deps.inputs,
      liveOutput = !noLiveOutput,
      repoRoot = effectiveRepoRoot,
      dbOverride = deps.inputs.dbPathOverride,
      runtimeProvenance = deps.runtimeProvenanceService.current(
        executablePathHint = deps.inputs.environment[RUNTIME_EXECUTABLE_ENV],
        classPath = deps.inputs.environment[RUNTIME_CLASSPATH_ENV] ?: deps.hostPlatform.jvmClassPath,
        javaCommand = ProcessHandle.current().info().command().orElse(null),
        pathSeparator = deps.inputs.environment[RUNTIME_PATH_SEPARATOR_ENV] ?: deps.hostPlatform.pathSeparator,
      ),
    )
    presenter.emitStartupProvenance()
    val report = deps.goalRunner.run(
      runRequest(runIssueKey, invokedAgentId, hydratedSelection, presenter, effectiveRepoRoot),
    )
    val payload = report.toGoalRunCliMap()
    deps.state.completeText(goalRunText(payload), payload, exitCode = payload.goalExitCode())
    drainTelemetryOnCompletion(deps.telemetryService, deps.inputs.dbPathOverride)
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
    dbPathOverride = deps.inputs.dbPathOverride,
    timeout = maxWallClockMinutes.takeIf { it > 0 }?.minutes,
    progressIdleTimeout = progressIdleTimeoutMinutes.takeIf { it > 0 }?.minutes,
    planningBudget = planningBudgetMinutes.takeIf { it > 0 }?.minutes,
    outputSink = presenter.outputSink(includeRawChildOutput = debugChildOutput),
    eventSink = presenter.eventSink(),
    codeReviewMode = parseCodeReviewMode(codeReviewMode),
    agentAddonSelection = hydratedSelection,
    stopAfterSubtaskId = stopAfterSubtask,
  )
}
