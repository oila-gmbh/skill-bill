package skillbill.cli.featuretask

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
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.WorkflowService
import skillbill.cli.core.CliRunInputs
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.invokingAgentResolutionHelp
import skillbill.cli.goal.DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES
import skillbill.cli.telemetry.drainTelemetryOnCompletion
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

abstract class FeatureTaskRuntimePhaseAgentCommand(
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  internal val repoRoot by option("--repo-root", help = "Repository root for phase agent runs.")
  internal val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help = "Per-phase wall-clock cap in minutes (default " +
      "$DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES). Hard ceiling even when a child process is still " +
      "alive. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES)
  internal val monitor by option(
    "--monitor",
    help = "Tee phase agent output and structured progress to this terminal.",
  ).flag(default = false)
  internal val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  internal val agentOverride by option(
    "--agent-override",
    help = "Agent to use for every phase run instead of the invoking agent. Wins over --agent and per-phase agents.",
  )
  internal val phaseAgents by option(
    "--phase-agent",
    help = "Per-phase agent assignment as phase=agent (e.g. --phase-agent plan=claude). Repeatable.",
  ).multiple()
  internal val phaseModels by option(
    "--phase-model",
    help = "Per-phase model directive as phase=model or phase=model@effort " +
      "(e.g. --phase-model plan=claude-opus-4-8@high). Wins over the config execution_matrix. Repeatable.",
  ).multiple()
  internal val goalParentIssueKey by option(
    "--goal-parent-issue-key",
    help = "Parent decomposed issue key for non-interactive goal-continuation runtime runs.",
  )
  internal val goalSubtaskId by option(
    "--goal-subtask-id",
    help = "Subtask id for non-interactive goal-continuation runtime runs.",
  ).int()
  internal val goalBranch by option(
    "--goal-branch",
    help = "Pre-created goal branch to reuse for non-interactive goal-continuation runtime runs.",
  )
  internal val goalParentWorkflowId by option(
    "--goal-parent-workflow-id",
    help = "Optional parent workflow id for non-interactive goal-continuation runtime runs.",
  )
  internal val goalLastResumableStep by option(
    "--goal-last-resumable-step",
    help = "Optional durable resume step supplied by the goal runner.",
  )
  internal val goalReviewBaseSha by option(
    "--goal-review-base-sha",
    help = "Review baseline commit captured by the goal runner before implementation.",
  )
  internal val goalBaselineUntrackedPaths by option(
    "--goal-baseline-untracked-path",
    help = "Baseline untracked path. Repeat for every path owned before this child starts.",
  ).multiple()
  internal val codeReviewModes by option(
    "--code-review-mode",
    help = "Review execution mode for this run: inline (default, one review subagent per " +
      "pass) or auto (also resolves inline). Supply at most once; a resumed workflow " +
      "remains pinned to its original mode.",
  ).multiple()
  internal val operatorDecisions by option(
    "--operator-decision",
    help = "Release a subtask paused on an unresolved Blocker or Major: " +
      "${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}. Supply at most once.",
  ).multiple()
  internal val suppressPr by option(
    "--suppress-pr",
    help = "Suppress the runtime PR phase. Required with goal-continuation options.",
  ).flag(default = false)
  internal val qualityGateSelections by option(
    "--quality-gate-selection",
    help = "Goal-child quality gate: build (compile proof) or validate (full collect-all). Defaults to validate.",
  ).multiple()
  internal val explicitWorkflowId by option(
    "--workflow-id",
    help = "Open the run under this exact workflow id instead of minting a new one. Used by the goal " +
      "driver's open-with-assigned-id path for a first runtime subtask run (distinct from resume).",
  )
  internal val agentAddonSelectionJson by option(
    "--agent-addon-selection-json",
    help = "Already-resolved ordered agent add-on selection JSON. Raw agent-addon tokens are not accepted here.",
  )

  protected fun resolveRunWorkflowId(
    workflowService: WorkflowService,
    inputs: CliRunInputs,
    issueKey: String,
    specPath: String,
    repoRoot: String,
  ): String = explicitWorkflowId?.takeIf(String::isNotBlank)
    ?: workflowService.openRuntimeWorkflowId(
      inputs,
      issueKey,
      specPath,
      repoRoot,
      if (goalParentIssueKey != null) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE,
    )

  protected fun validateRuntimeRunConfiguration(deps: FeatureTaskRuntimeRunDependencies) {
    prepareRuntimeRun(deps)
  }

  protected fun executeRuntimeRun(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    specPath: String,
    workflowId: () -> String,
  ) {
    val state = deps.state
    val requestedReviewMode = requestedCodeReviewMode()
    val goalContinuation = parseGoalContinuationContext(requestedReviewMode, deps.inputs.environment)
    val prepared = prepareRuntimeRun(deps)
    val resolvedWorkflowId = workflowId()
    val report = deps.workerCoordinator.runOwned(resolvedWorkflowId, deps.inputs.dbPathOverride) {
      deps.runner.run(
        FeatureTaskRuntimeRunRequest(
          issueKey = issueKey,
          workflowId = resolvedWorkflowId,
          sessionId =
          "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$resolvedWorkflowId",
          runInvariants = deps.runInvariantsSource.read(Path.of(specPath)).copy(
            agentAddonSelection = prepared.agentAddonSelection.persisted,
          ),
          invokedAgentId = prepared.invokedAgentId,
          agentAssignment = prepared.agentAssignment,
          modelAssignment = prepared.modelAssignment,
          compactionSettings = prepared.compactionSettings,
          environment = deps.inputs.environment,
          dbPathOverride = deps.inputs.dbPathOverride,
          repoRoot = prepared.repoRoot,
          timeout = maxWallClockMinutes.takeIf { it > 0 }?.minutes,
          requestedCodeReviewMode = requestedReviewMode,
          goalContinuation = goalContinuation,
          operatorDecision = requestedOperatorDecision(),
          agentAddonSelection = prepared.agentAddonSelection,
          eventSink = runtimeRunEventSink(deps.inputs, monitor),
        ),
      )
    }
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(payload), payload, exitCode = payload.runtimeRunExitCode())
    drainTelemetryOnCompletion(deps.telemetryService, deps.inputs.dbPathOverride)
  }

  internal fun resolveSpecPath(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    explicitSpecPath: String?,
  ): String {
    val result = deps.specPathResolver.resolve(
      FeatureSpecPathResolveInput(
        issueKey = issueKey,
        explicitSpecPath = explicitSpecPath,
        repoRoot = repoRoot?.let(Path::of) ?: deps.inputs.repositoryRoot,
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
}

@Inject
class FeatureTaskRuntimeRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
  featureTaskRuntimeExplicitRunCommand: FeatureTaskRuntimeExplicitRunCommand,
  control: FeatureTaskRuntimeControlSubcommands,
  rejectedOutput: FeatureTaskRejectedOutputSubcommands,
) : FeatureTaskRuntimePhaseAgentCommand(
  "feature-task",
  "Run the runtime-driven feature-task phase loop in the foreground.",
) {
  private val issueKey by argument(help = "Issue key the run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(
      featureTaskRuntimeExplicitRunCommand,
      control.status,
      control.resume,
      control.abandon,
      control.retryBlocked,
      control.repairIdentity,
      control.lookup,
      rejectedOutput.inspect,
      rejectedOutput.cleanup,
    )
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
      workflowId = { resolveRunWorkflowId(workflowService, deps.inputs, runIssueKey, runSpecPath, repoRoot ?: ".") },
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
    val runSpecPath = resolveSpecPath(deps, issueKey, specPath)
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = runSpecPath,
      workflowId = { resolveRunWorkflowId(workflowService, deps.inputs, issueKey, runSpecPath, repoRoot ?: ".") },
    )
  }
}
