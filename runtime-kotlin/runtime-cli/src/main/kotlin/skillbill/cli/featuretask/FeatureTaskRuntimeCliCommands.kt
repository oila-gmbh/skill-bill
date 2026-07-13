@file:Suppress("TooManyFunctions")

package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskRuntimeAgentResolver
import skillbill.application.featuretask.FeatureTaskRuntimeModelResolver
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeModelAssignment
import skillbill.application.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.workflow.WorkflowService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.refuseRuntimeRefusedAgents
import skillbill.cli.core.refuseUnsupportedModelDirectives
import skillbill.config.model.PhaseModelDirective
import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.review.CodeReviewExecutionMode
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Each command only validates input and delegates to an application service; the spec read goes
 * through the injected [FeatureTaskRuntimeRunInvariantsSource] port so this module performs no file IO.
 */
@Inject
data class FeatureTaskRuntimeRunDependencies(
  val runner: FeatureTaskRuntimeRunner,
  val runInvariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val specPathResolver: FeatureSpecPathResolverPort,
  val configResolutionService: ConfigResolutionService,
  val state: CliRunState,
)

abstract class FeatureTaskRuntimePhaseAgentCommand(
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  protected val repoRoot by option("--repo-root", help = "Repository root for phase agent runs.")
  protected val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Optional per-phase wall-clock cap in minutes (must be >= 1). Default is no wall-clock cap.",
  ).int().restrictTo(min = 1)
  protected val monitor by option(
    "--monitor",
    help = "Tee phase agent output and structured progress to this terminal.",
  ).flag(default = false)
  protected val agent by option(
    "--agent",
    help = "Agent invoking bill-feature-task. Resolution order: --agent, then SKILL_BILL_AGENT, then the " +
      "detected invoking-agent execution context, then a documented last-resort default ($DEFAULT_RUNTIME_AGENT).",
  )
  protected val agentOverride by option(
    "--agent-override",
    help = "Agent to use for every phase run instead of the invoking agent. Wins over --agent and per-phase agents.",
  )
  protected val phaseAgents by option(
    "--phase-agent",
    help = "Per-phase agent assignment as phase=agent (e.g. --phase-agent plan=claude). Repeatable.",
  ).multiple()
  protected val phaseModels by option(
    "--phase-model",
    help = "Per-phase model directive as phase=model or phase=model@effort " +
      "(e.g. --phase-model plan=claude-opus-4-8@high). Wins over the config execution_matrix. Repeatable.",
  ).multiple()
  protected val goalParentIssueKey by option(
    "--goal-parent-issue-key",
    help = "Parent decomposed issue key for non-interactive goal-continuation runtime runs.",
  )
  protected val goalSubtaskId by option(
    "--goal-subtask-id",
    help = "Subtask id for non-interactive goal-continuation runtime runs.",
  ).int()
  protected val goalBranch by option(
    "--goal-branch",
    help = "Pre-created goal branch to reuse for non-interactive goal-continuation runtime runs.",
  )
  protected val goalParentWorkflowId by option(
    "--goal-parent-workflow-id",
    help = "Optional parent workflow id for non-interactive goal-continuation runtime runs.",
  )
  protected val goalLastResumableStep by option(
    "--goal-last-resumable-step",
    help = "Optional durable resume step supplied by the goal runner.",
  )
  protected val goalReviewBaseSha by option(
    "--goal-review-base-sha",
    help = "Review baseline commit captured by the goal runner before implementation.",
  )
  protected val goalBaselineUntrackedPaths by option(
    "--goal-baseline-untracked-path",
    help = "Baseline untracked path. Repeat for every path owned before this child starts.",
  ).multiple()
  protected val parallelReviewAgent by option(
    "--parallel-review-agent",
    help = "Run the review phase with a second parallel agent lane. " +
      "Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
  )
  protected val codeReviewModes by option(
    "--code-review-mode",
    help = "Review execution mode: auto (default), inline, or delegated. " +
      "Supply at most once; a resumed workflow remains pinned to its original mode.",
  ).multiple()
  protected val suppressPr by option(
    "--suppress-pr",
    help = "Suppress the runtime PR phase. Required with goal-continuation options.",
  ).flag(default = false)
  protected val explicitWorkflowId by option(
    "--workflow-id",
    help = "Open the run under this exact workflow id instead of minting a new one. Used by the goal " +
      "driver's open-with-assigned-id path for a first runtime subtask run (distinct from resume).",
  )

  protected fun resolveRunWorkflowId(
    workflowService: WorkflowService,
    state: CliRunState,
    issueKey: String,
  ): String =
    explicitWorkflowId?.takeIf(String::isNotBlank) ?: openRuntimeWorkflowId(workflowService, state, issueKey)

  // Refuses before a workflow is opened, a branch resolved, or a phase spawned: opencode is
  // prose-only because its foreground Bash tool is hard-killed at 120s and per-phase output
  // cannot be harvested back. Enumerates every route the runtime agent can resolve from and
  // defers the predicate + message to the shared gate.
  protected fun refuseUnsupportedRuntimeAgent(environment: Map<String, String>) {
    refuseRuntimeRefusedAgents(
      buildList {
        add(resolveInvokedRuntimeAgentId(agent, environment))
        agentOverride?.takeIf(String::isNotBlank)?.let { add(it) }
        addAll(parsePhaseAgents(phaseAgents).values)
        parallelReviewAgent?.takeIf(String::isNotBlank)?.let { add(it) }
      },
    )
  }

  protected fun executeRuntimeRun(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    specPath: String,
    workflowId: () -> String,
  ) {
    val state = deps.state
    val requestedReviewMode = requestedCodeReviewMode()
    val goalContinuation = parseGoalContinuationContext(requestedReviewMode)
    val prepared = prepareRuntimeRun(deps)
    val report = deps.runner.run(
      FeatureTaskRuntimeRunRequest(
        issueKey = issueKey,
        workflowId = workflowId(),
        sessionId = "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$issueKey",
        runInvariants = deps.runInvariantsSource.read(Path.of(specPath)),
        invokedAgentId = prepared.invokedAgentId,
        agentAssignment = prepared.agentAssignment,
        modelAssignment = prepared.modelAssignment,
        environment = state.environment,
        dbPathOverride = state.dbOverride,
        repoRoot = prepared.repoRoot,
        timeout = maxWallClockMinutes?.minutes,
        parallelReviewAgent = parallelReviewAgent?.takeIf(String::isNotBlank),
        requestedCodeReviewMode = requestedReviewMode,
        goalContinuation = goalContinuation,
        eventSink = runtimeRunEventSink(state, monitor),
      ),
    )
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(payload), payload, exitCode = payload.runtimeRunExitCode())
  }

  private fun prepareRuntimeRun(deps: FeatureTaskRuntimeRunDependencies): PreparedRuntimeRun {
    val environment = deps.state.environment
    refuseUnsupportedRuntimeAgent(environment)
    val repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize()
    val invokedAgentId = resolveInvokedRuntimeAgentId(agent, environment)
    val agentAssignment = FeatureTaskRuntimeAgentAssignment(
      perPhaseAgentIds = parsePhaseAgents(phaseAgents),
      override = agentOverride?.takeIf(String::isNotBlank),
    )
    val modelAssignment = FeatureTaskRuntimeModelAssignment(
      perPhaseDirectives = parsePhaseModels(phaseModels),
      matrix = deps.configResolutionService.resolveExecutionMatrix(),
    )
    val resolvedAgentIds = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimeAgentResolver.resolve(phaseId, agentAssignment, invokedAgentId).resolvedAgentId
    }
    val directives = resolvedAgentIds.mapNotNull { (phaseId, resolvedAgentId) ->
      FeatureTaskRuntimeModelResolver.resolve(phaseId, resolvedAgentId, modelAssignment)?.let { directive ->
        phaseId to directive
      }
    }.toMap()
    refuseUnsupportedModelDirectives(directives, resolvedAgentIds)
    return PreparedRuntimeRun(repoRoot, invokedAgentId, agentAssignment, modelAssignment)
  }

  protected fun resolveSpecPath(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    explicitSpecPath: String?,
  ): String {
    val result = deps.specPathResolver.resolve(
      FeatureSpecPathResolveInput(
        issueKey = issueKey,
        explicitSpecPath = explicitSpecPath,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
      ),
    )
    return when (result) {
      is FeatureSpecPathResolveResult.Explicit -> result.specPath
      is FeatureSpecPathResolveResult.SingleMatch -> result.specPath
      is FeatureSpecPathResolveResult.NoMatch -> throw UsageError(
        "spec_path is required for feature-task run; no .feature-specs match found for '${result.issueKey}' " +
          "under ${result.specsRoot}.",
      )
      is FeatureSpecPathResolveResult.Ambiguous -> throw UsageError(
        "spec_path is required for feature-task run; multiple .feature-specs matches found for '${result.issueKey}': " +
          result.matches.joinToString(", "),
      )
    }
  }

  private fun parseGoalContinuationContext(
    requestedReviewMode: CodeReviewExecutionMode?,
  ): FeatureTaskRuntimeGoalContinuationContext? {
    val supplied = listOf(goalParentIssueKey, goalSubtaskId, goalBranch).count { it != null } +
      if (suppressPr) 1 else 0
    if (supplied == 0) {
      return null
    }
    val missing = goalContinuationMissingFields()
    if (missing.isNotEmpty()) {
      throw UsageError("${missing.joinToString()} required with goal-continuation options.")
    }
    return FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = requireNotNull(goalParentIssueKey),
      subtaskId = requireNotNull(goalSubtaskId),
      goalBranch = requireNotNull(goalBranch),
      suppressPr = true,
      parentWorkflowId = goalParentWorkflowId?.takeIf(String::isNotBlank),
      lastResumableStep = goalLastResumableStep?.takeIf(String::isNotBlank),
      codeReviewMode = requestedReviewMode,
      parallelReviewAgent = parallelReviewAgent?.takeIf(String::isNotBlank),
      reviewBaseline = requireNotNull(goalReviewBaseSha?.takeIf(String::isNotBlank)) { "--goal-review-base-sha is required with goal-continuation options." }.let { base ->
        GoalSubtaskReviewBaseline(base, goalBaselineUntrackedPaths.distinct().sorted())
      },
    )
  }

  private fun requestedCodeReviewMode(): CodeReviewExecutionMode? {
    val modes = codeReviewModes.map(::parseRequestedCodeReviewMode)
    return when (modes.size) {
      0 -> null
      1 -> modes.single()
      else -> {
        val rawModes = codeReviewModes.joinToString(", ")
        if (modes.distinct().size == 1) {
          throw UsageError(
            "Duplicate --code-review-mode '$rawModes' is not allowed; supply it at most once.",
          )
        }
        throw UsageError(
          "Conflicting --code-review-mode values '$rawModes' are not allowed; supply exactly one mode.",
        )
      }
    }
  }

  private fun parseRequestedCodeReviewMode(raw: String): CodeReviewExecutionMode =
    CodeReviewExecutionMode.entries.firstOrNull { it.wireValue == raw }
      ?: throw UsageError(
        "Unknown code-review execution mode '$raw'. Allowed: " +
          "${CodeReviewExecutionMode.entries.joinToString { it.wireValue }}.",
      )

  private fun goalContinuationMissingFields(): List<String> = buildList {
    if (goalParentIssueKey.isNullOrBlank()) add("--goal-parent-issue-key is")
    if (goalSubtaskId == null) add("--goal-subtask-id is")
    if (goalBranch.isNullOrBlank()) add("--goal-branch is")
    if (goalReviewBaseSha.isNullOrBlank()) add("--goal-review-base-sha is")
    if (!suppressPr) add("--suppress-pr is")
  }
}

@Inject
class FeatureTaskRuntimeRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
  featureTaskRuntimeExplicitRunCommand: FeatureTaskRuntimeExplicitRunCommand,
  featureTaskRuntimeStatusCommand: FeatureTaskRuntimeStatusCommand,
  featureTaskRuntimeResumeCommand: FeatureTaskRuntimeResumeCommand,
) : FeatureTaskRuntimePhaseAgentCommand(
  "feature-task",
  "Run the runtime-driven feature-task phase loop in the foreground.",
) {
  private val issueKey by argument(help = "Issue key the run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(featureTaskRuntimeExplicitRunCommand, featureTaskRuntimeStatusCommand, featureTaskRuntimeResumeCommand)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task run.")
    val runSpecPath = resolveSpecPath(deps, runIssueKey, specPath)
    executeRuntimeRun(
      deps = deps,
      issueKey = runIssueKey,
      specPath = runSpecPath,
      workflowId = { resolveRunWorkflowId(workflowService, deps.state, runIssueKey) },
    )
  }
}

/**
 * Explicit `run` subcommand mirroring the documented `feature-task run <issue_key>
 * <spec_path>` form. Without it, clikt silently consumes `run` as the optional issue-key
 * positional of the parent command and misparses the remaining arguments.
 */
@Inject
class FeatureTaskRuntimeExplicitRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
) : FeatureTaskRuntimePhaseAgentCommand(
  "run",
  "Run the feature-task phase loop (explicit form of the parent command's default run).",
) {
  private val issueKey by argument(help = "Issue key the run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = resolveSpecPath(deps, issueKey, specPath),
      workflowId = { resolveRunWorkflowId(workflowService, deps.state, issueKey) },
    )
  }
}

@Inject
class FeatureTaskRuntimeStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only feature-task phase status.") {
  private val workflowId by argument(help = "Runtime workflow id whose phase status to show.")

  override fun run() {
    val projection = statusService.status(
      FeatureTaskRuntimeStatusRequest(workflowId = workflowId, dbPathOverride = state.dbOverride),
    )
    val payload = projection.toRuntimeStatusCliMap(workflowId)
    state.completeText(runtimeStatusText(payload), payload, exitCode = payload.runtimeStatusExitCode())
  }
}

@Inject
class FeatureTaskRuntimeResumeCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
) : FeatureTaskRuntimePhaseAgentCommand(
  "resume",
  "Resume a feature-task run against an existing workflow id.",
) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = specPath,
      workflowId = { workflowId },
    )
  }
}

private const val FEATURE_TASK_RUNTIME_DEPRECATION_NOTE: String =
  "feature-task-runtime is a deprecated alias for feature-task. Use feature-task; behavior is unchanged.\n"

/**
 * SKILL-67 Subtask 1 (AC2): hidden deprecated alias for the canonical `feature-task`
 * command. Reuses the same dependencies, services, and
 * [FeatureTaskRuntimePhaseAgentCommand] base, so behavior is identical to the canonical
 * command; the only difference is a stderr deprecation note emitted on every invocation
 * (the parent `run()` always executes before any subcommand). Kept registered for the
 * removal window.
 */
@Inject
class FeatureTaskRuntimeDeprecatedRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
  featureTaskRuntimeDeprecatedExplicitRunCommand: FeatureTaskRuntimeDeprecatedExplicitRunCommand,
  featureTaskRuntimeDeprecatedStatusCommand: FeatureTaskRuntimeDeprecatedStatusCommand,
  featureTaskRuntimeDeprecatedResumeCommand: FeatureTaskRuntimeDeprecatedResumeCommand,
) : FeatureTaskRuntimePhaseAgentCommand(
  "feature-task-runtime",
  "Deprecated alias for feature-task. Use feature-task; behavior is unchanged.",
) {
  override val hiddenFromHelp: Boolean = true

  private val issueKey by argument(help = "Issue key the run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(
      featureTaskRuntimeDeprecatedExplicitRunCommand,
      featureTaskRuntimeDeprecatedStatusCommand,
      featureTaskRuntimeDeprecatedResumeCommand,
    )
  }

  override fun run() {
    deps.state.liveStderr(FEATURE_TASK_RUNTIME_DEPRECATION_NOTE)
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task run.")
    val runSpecPath = resolveSpecPath(deps, runIssueKey, specPath)
    executeRuntimeRun(
      deps = deps,
      issueKey = runIssueKey,
      specPath = runSpecPath,
      workflowId = { openRuntimeWorkflowId(workflowService, deps.state, runIssueKey) },
    )
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedExplicitRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
) : FeatureTaskRuntimePhaseAgentCommand(
  "run",
  "Run the feature-task phase loop (explicit form of the parent command's default run).",
) {
  private val issueKey by argument(help = "Issue key the run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = resolveSpecPath(deps, issueKey, specPath),
      workflowId = { openRuntimeWorkflowId(workflowService, deps.state, issueKey) },
    )
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only feature-task phase status.") {
  private val workflowId by argument(help = "Runtime workflow id whose phase status to show.")

  override fun run() {
    val projection = statusService.status(
      FeatureTaskRuntimeStatusRequest(workflowId = workflowId, dbPathOverride = state.dbOverride),
    )
    val payload = projection.toRuntimeStatusCliMap(workflowId)
    state.completeText(runtimeStatusText(payload), payload, exitCode = payload.runtimeStatusExitCode())
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedResumeCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
) : FeatureTaskRuntimePhaseAgentCommand(
  "resume",
  "Resume a feature-task run against an existing workflow id.",
) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = specPath,
      workflowId = { workflowId },
    )
  }
}

private fun openRuntimeWorkflowId(workflowService: WorkflowService, state: CliRunState, issueKey: String?): String =
  when (val opened = workflowService.open(WorkflowFamilyKind.TASK_RUNTIME, issueKey = issueKey, dbOverride = state.dbOverride)) {
    is WorkflowOpenResult.Ok -> opened.workflowId
    is WorkflowOpenResult.Error -> throw UsageError(
      "Could not open a feature-task workflow: ${opened.error}",
    )
  }

private fun runtimeRunEventSink(state: CliRunState, monitor: Boolean): FeatureTaskRuntimeRunEventSink = if (!monitor) {
  FeatureTaskRuntimeRunEventSink.NONE
} else {
  FeatureTaskRuntimeRunEventSink { event ->
    state.liveStdout(event.runtimeProgressLine())
  }
}

private fun FeatureTaskRuntimeRunEvent.runtimeProgressLine(): String = when (this) {
  is FeatureTaskRuntimeRunEvent.RunStarted ->
    "feature-task-runtime $workflowId: run started feature_size=$featureSize\n"
  is FeatureTaskRuntimeRunEvent.BranchResolved ->
    "feature-task-runtime $workflowId: branch ${if (reused) "reused" else "created"} $branch\n"
  is FeatureTaskRuntimeRunEvent.BranchSetupBlocked ->
    "feature-task-runtime $workflowId: branch setup blocked at phase $phaseId: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.PhaseStarted ->
    "feature-task-runtime $workflowId: phase $phaseId ${if (resumed) "resumed" else "started"} " +
      "agent=$resolvedAgentId attempt=$attemptCount" +
      model?.let { " model=$it" }.orEmpty() +
      effort?.let { " effort=$it" }.orEmpty() +
      "\n"
  is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration ->
    "feature-task-runtime $workflowId: phase $phaseId fix_loop attempt=$attemptCount iteration=$fixLoopIteration\n"
  is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
    "feature-task-runtime $workflowId: phase $phaseId completed agent=$resolvedAgentId attempt=$attemptCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
    "feature-task-runtime $workflowId: phase $phaseId blocked attempt=$attemptCount: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning ->
    "feature-task-runtime $workflowId: decomposed at planning into $subtaskCount subtasks: $reason. " +
      "Work the first subtask first.\n"
}

private fun parsePhaseAgents(rawAssignments: List<String>): Map<String, String> {
  val parsed = LinkedHashMap<String, String>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      throw UsageError("--phase-agent must be phase=agent, e.g. --phase-agent plan=claude (got '$assignment').")
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val agentId = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      throw UsageError(
        "--phase-agent phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    parsed[phaseId] = agentId
  }
  return parsed
}

private fun parsePhaseModels(rawAssignments: List<String>): Map<String, PhaseModelDirective> {
  val parsed = LinkedHashMap<String, PhaseModelDirective>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      invalidPhaseModel(
        "--phase-model must be phase=model[@effort], e.g. --phase-model plan=claude-opus-4-8@high " +
          "(got '$assignment').",
      )
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val modelAndEffort = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidPhaseModel(
        "--phase-model phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    if (modelAndEffort.count { it == '@' } > 1) {
      invalidPhaseModel("--phase-model allows at most one @ separating model and effort (got '$assignment').")
    }
    val effortSeparator = modelAndEffort.indexOf('@')
    val model = modelAndEffort.substringBefore('@').trim()
    val effort = if (effortSeparator == -1) null else modelAndEffort.substring(effortSeparator + 1).trim()
    if (model.isBlank() || effort?.isBlank() == true) {
      invalidPhaseModel("--phase-model requires non-blank model and effort segments (got '$assignment').")
    }
    parsed[phaseId] = PhaseModelDirective(model = model, effort = effort)
  }
  return parsed
}

private fun invalidPhaseModel(message: String): Nothing = throw UsageError(message)

private data class PreparedRuntimeRun(
  val repoRoot: Path,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
)

private fun resolveInvokedRuntimeAgentId(explicitAgent: String?, environment: Map<String, String>): String =
  explicitAgent?.takeIf(String::isNotBlank)
    ?: environment["SKILL_BILL_AGENT"]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(environment)?.id
    ?: DEFAULT_RUNTIME_AGENT

private fun FeatureTaskRuntimeRunReport.toRuntimeRunCliMap(): Map<String, Any?> = when (this) {
  is FeatureTaskRuntimeRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Blocked -> linkedMapOf(
    "status" to "blocked",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "last_incomplete_phase" to lastIncompletePhase,
    "blocked_reason" to blockedReason,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Decomposed -> linkedMapOf(
    "status" to "decomposed",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "reason" to reason,
    "completed_phases" to completedPhaseIds,
    "parent_spec_path" to parentSpecPath,
    "decomposition_manifest_path" to decompositionManifestPath,
    "subtask_spec_paths" to subtaskSpecPaths,
    "subtask_count" to subtaskSpecPaths.size,
    "guidance" to DECOMPOSE_GUIDANCE,
  )
}

private fun Map<String, Any?>.withSubtaskOutcome(
  outcome: skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome?,
): Map<String, Any?> = if (outcome == null) {
  this
} else {
  LinkedHashMap(this).apply {
    put(
      "subtask_outcome",
      linkedMapOf(
        "issue_key" to outcome.issueKey,
        "subtask_id" to outcome.subtaskId,
        "status" to outcome.status,
        "commit_sha" to outcome.commitSha,
        "workflow_id" to outcome.workflowId,
        "blocked_reason" to outcome.blockedReason,
        "last_resumable_step" to outcome.lastResumableStep,
        "finalizing_agent_id" to outcome.finalizingAgentId,
        "participating_agent_ids" to outcome.participatingAgentIds,
      ),
    )
  }
}

private fun Map<String, Any?>.runtimeRunExitCode(): Int = if (isTerminalSuccessStatus()) 0 else 1

private fun Map<String, Any?>.isTerminalSuccessStatus(): Boolean = this["status"] in setOf("complete", "decomposed")

private fun runtimeRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["issue_key"]}")
  appendLine("workflow_id: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"]}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("completed_phases: ${(payload["completed_phases"] as? List<*>).orEmpty().joinToString()}")
  payload["last_incomplete_phase"]?.let { appendLine("last_incomplete_phase: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
  (payload["subtask_outcome"] as? Map<*, *>)?.let { outcome -> appendSubtaskOutcome(outcome) }
  payload["reason"]?.let { appendLine("decomposition_reason: $it") }
  payload["subtask_count"]?.let { appendLine("subtask_count: $it") }
  payload["parent_spec_path"]?.let { appendLine("parent_spec_path: $it") }
  payload["decomposition_manifest_path"]?.let { appendLine("decomposition_manifest_path: $it") }
  (payload["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
  payload["guidance"]?.let { appendLine("guidance: $it") }
}

private fun StringBuilder.appendSubtaskOutcome(outcome: Map<*, *>) {
  appendLine("subtask_outcome:")
  appendLine("  issue_key: ${outcome["issue_key"]}")
  appendLine("  subtask_id: ${outcome["subtask_id"]}")
  appendLine("  status: ${outcome["status"]}")
  appendLine("  commit_sha: ${outcome["commit_sha"] ?: "none"}")
  appendLine("  workflow_id: ${outcome["workflow_id"]}")
  appendLine("  last_resumable_step: ${outcome["last_resumable_step"]}")
  outcome["finalizing_agent_id"]?.let { appendLine("  finalizing_agent_id: $it") }
  (outcome["participating_agent_ids"] as? List<*>)?.takeIf { it.isNotEmpty() }
    ?.let { appendLine("  participating_agent_ids: ${it.joinToString()}") }
  outcome["blocked_reason"]?.let { appendLine("  blocked_reason: $it") }
}

private fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      "status" to "ok",
      "workflow_id" to it.workflowId,
      "feature_size" to it.featureSize,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_phase" to it.currentPhaseId,
      "resolved_branch" to it.resolvedBranch,
      "finalizing_agent_id" to it.finalizingAgentId,
      "decompose_terminal" to it.decomposeTerminal?.let { terminal ->
        linkedMapOf(
          "reason" to terminal.reason,
          "parent_spec_path" to terminal.parentSpecPath,
          "decomposition_manifest_path" to terminal.decompositionManifestPath,
          "subtask_spec_paths" to terminal.subtaskSpecPaths,
          "subtask_count" to terminal.subtaskCount,
          "guidance" to DECOMPOSE_GUIDANCE,
        )
      },
      "phases" to it.phases.map(FeatureTaskRuntimePhaseStatus::toRuntimePhaseStatusCliMap),
    )
  } ?: linkedMapOf(
    "status" to "not_found",
    "workflow_id" to workflowId,
    "feature_size" to null,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_phase" to null,
    "resolved_branch" to null,
    "finalizing_agent_id" to null,
    "decompose_terminal" to null,
    "phases" to emptyList<Map<String, Any?>>(),
  )

private fun FeatureTaskRuntimePhaseStatus.toRuntimePhaseStatusCliMap(): Map<String, Any?> = linkedMapOf(
  "phase_id" to phaseId,
  "status" to status,
  "attempt_count" to attemptCount,
  "resolved_agent_id" to resolvedAgentId,
  "finished" to finished,
)

private fun Map<String, Any?>.runtimeStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

private fun runtimeStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"] ?: "unknown"}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_phase: ${payload["current_phase"] ?: "none"}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("finalizing_agent: ${payload["finalizing_agent_id"] ?: "none"}")
  (payload["decompose_terminal"] as? Map<*, *>)?.let { terminal ->
    appendLine("decomposition_reason: ${terminal["reason"]}")
    appendLine("subtask_count: ${terminal["subtask_count"]}")
    appendLine("parent_spec_path: ${terminal["parent_spec_path"]}")
    appendLine("decomposition_manifest_path: ${terminal["decomposition_manifest_path"]}")
    (terminal["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
    appendLine("guidance: ${terminal["guidance"]}")
  }
  (payload["phases"] as? List<*>).orEmpty().forEach { rawPhase ->
    val phase = rawPhase as? Map<*, *> ?: return@forEach
    appendLine(
      "phase: id=${phase["phase_id"]} status=${phase["status"]} attempt=${phase["attempt_count"]} " +
        "agent=${phase["resolved_agent_id"] ?: "none"} finished=${phase["finished"]}",
    )
  }
}

// Last-resort default used only when no explicit flag, env, or detected invoking-agent context resolves.
private const val DEFAULT_RUNTIME_AGENT = "codex"

private const val DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."
