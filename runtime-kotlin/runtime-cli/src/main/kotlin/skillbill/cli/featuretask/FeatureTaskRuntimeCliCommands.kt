package skillbill.cli.featuretask

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
import com.github.ajalt.clikt.parameters.types.restrictTo
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskRuntimeAgentResolver
import skillbill.application.featuretask.FeatureTaskRuntimeModelResolver
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.featuretask.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeModelAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.telemetry.TelemetryService
import skillbill.application.workflow.WorkflowService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.invokingAgentResolutionHelp
import skillbill.cli.core.refuseUnavailableAgentLaunchers
import skillbill.cli.core.refuseUnsupportedModelDirectives
import skillbill.cli.core.requireInvokingAgentId
import skillbill.cli.telemetry.drainTelemetryOnCompletion
import skillbill.config.model.CompactionSettings
import skillbill.config.model.PhaseModelDirective
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

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
    help = invokingAgentResolutionHelp("--agent"),
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
  protected val codeReviewModes by option(
    "--code-review-mode",
    help = "Review execution mode for this run: inline (default, one review subagent per " +
      "pass) or auto (also resolves inline). Supply at most once; a resumed workflow " +
      "remains pinned to its original mode.",
  ).multiple()
  protected val operatorDecisions by option(
    "--operator-decision",
    help = "Release a subtask paused on an unresolved Blocker or Major: " +
      "${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}. Supply at most once.",
  ).multiple()
  protected val suppressPr by option(
    "--suppress-pr",
    help = "Suppress the runtime PR phase. Required with goal-continuation options.",
  ).flag(default = false)
  protected val qualityGateSelections by option(
    "--quality-gate-selection",
    help = "Goal-child quality gate: build (compile proof) or validate (full collect-all). Defaults to validate.",
  ).multiple()
  protected val explicitWorkflowId by option(
    "--workflow-id",
    help = "Open the run under this exact workflow id instead of minting a new one. Used by the goal " +
      "driver's open-with-assigned-id path for a first runtime subtask run (distinct from resume).",
  )
  protected val agentAddonSelectionJson by option(
    "--agent-addon-selection-json",
    help = "Already-resolved ordered agent add-on selection JSON. Raw agent-addon tokens are not accepted here.",
  )

  protected fun resolveRunWorkflowId(
    workflowService: WorkflowService,
    state: CliRunState,
    issueKey: String,
    specPath: String,
    repoRoot: String,
  ): String = explicitWorkflowId?.takeIf(String::isNotBlank)
    ?: workflowService.openRuntimeWorkflowId(
      state,
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
    val goalContinuation = parseGoalContinuationContext(requestedReviewMode)
    val prepared = prepareRuntimeRun(deps)
    val resolvedWorkflowId = workflowId()
    val report = deps.workerCoordinator.runOwned(resolvedWorkflowId, state.dbOverride) {
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
          environment = state.environment,
          dbPathOverride = state.dbOverride,
          repoRoot = prepared.repoRoot,
          timeout = maxWallClockMinutes?.minutes,
          requestedCodeReviewMode = requestedReviewMode,
          goalContinuation = goalContinuation,
          operatorDecision = requestedOperatorDecision(),
          agentAddonSelection = prepared.agentAddonSelection,
          eventSink = runtimeRunEventSink(state, monitor),
        ),
      )
    }
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(payload), payload, exitCode = payload.runtimeRunExitCode())
    drainTelemetryOnCompletion(deps.telemetryService, state.dbOverride)
  }

  private fun prepareRuntimeRun(deps: FeatureTaskRuntimeRunDependencies): PreparedRuntimeRun {
    val environment = deps.state.environment
    val repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize()
    val invokedAgentId = resolveInvokedRuntimeAgentId(agent, environment)
    val phaseAgentMap = parsePhaseAgents(phaseAgents).toMutableMap()
    val agentAssignment = FeatureTaskRuntimeAgentAssignment(
      perPhaseAgentIds = phaseAgentMap,
      override = agentOverride?.takeIf(String::isNotBlank),
    )
    val modelAssignment = FeatureTaskRuntimeModelAssignment(
      perPhaseDirectives = parsePhaseModels(phaseModels),
      matrix = deps.configResolutionService.resolveExecutionMatrix(),
    )
    val compactionSettings = deps.configResolutionService.resolveCompactionSettings()
    val resolvedAgentIds = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimeAgentResolver.resolve(phaseId, agentAssignment, invokedAgentId).resolvedAgentId
    }
    val directives = resolvedAgentIds.mapNotNull { (phaseId, resolvedAgentId) ->
      FeatureTaskRuntimeModelResolver.resolve(phaseId, resolvedAgentId, modelAssignment)?.let { directive ->
        phaseId to directive
      }
    }.toMap()
    refuseUnsupportedModelDirectives(directives, resolvedAgentIds)
    val receivingAgents = buildList {
      addAll(resolvedAgentIds.values)
      addAll(parsePhaseAgents(phaseAgents).values)
      agentOverride?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct()
    refuseUnavailableAgentLaunchers(receivingAgents, deps.executableLookup)
    val persistedSelection = parseAgentAddonSelection(agentAddonSelectionJson)
    val hydratedSelection = if (persistedSelection.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      deps.agentAddonSelectionPort.verifyPersisted(
        persistedSelection,
        AgentAddonConsumer.BILL_FEATURE,
        receivingAgents,
      )
    }
    return PreparedRuntimeRun(
      repoRoot,
      invokedAgentId,
      agentAssignment,
      modelAssignment,
      compactionSettings,
      hydratedSelection,
    )
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
      validationDepth = ValidationDepth.FULL,
      qualityGateSelection = requestedQualityGateSelection(),
      reviewBaseline = requireNotNull(goalReviewBaseSha?.takeIf(String::isNotBlank)) {
        "--goal-review-base-sha is required with goal-continuation options."
      }.let { base ->
        GoalSubtaskReviewBaseline(base, goalBaselineUntrackedPaths.distinct().sorted())
      },
    )
  }

  private fun requestedQualityGateSelection(): FeatureTaskRuntimeQualityGateSelection {
    val fromEnv = System.getenv("SKILL_BILL_QUALITY_GATE_SELECTION")
      ?.takeIf(String::isNotBlank)
      ?.let(FeatureTaskRuntimeQualityGateSelection::fromWire)
    val fromCli = when (qualityGateSelections.size) {
      0 -> null
      1 -> FeatureTaskRuntimeQualityGateSelection.fromWire(qualityGateSelections.single())
      else -> {
        val raw = qualityGateSelections.joinToString(", ")
        if (qualityGateSelections.distinct().size == 1) {
          throw UsageError("Duplicate --quality-gate-selection '$raw' is not allowed; supply it at most once.")
        }
        throw UsageError(
          "Conflicting --quality-gate-selection values '$raw' are not allowed; supply exactly one selection.",
        )
      }
    }
    return fromCli ?: fromEnv ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
  }

  private fun requestedOperatorDecision(): GoalSubtaskOperatorDecision? {
    if (operatorDecisions.size > 1) {
      throw UsageError(
        "Conflicting --operator-decision values '${operatorDecisions.joinToString(", ")}' are not allowed; " +
          "supply exactly one decision.",
      )
    }
    return operatorDecisions.singleOrNull()?.let { raw ->
      GoalSubtaskOperatorDecision.entries.firstOrNull { it.wireValue == raw }
        ?: throw UsageError(
          "Unknown operator decision '$raw'. Allowed: " +
            "${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}.",
        )
    }
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

  private fun parseRequestedCodeReviewMode(raw: String): CodeReviewExecutionMode = try {
    RuntimeOwnedReviewMode.parse(raw)
  } catch (error: IllegalArgumentException) {
    throw UsageError(error.message ?: "Unknown code-review execution mode.").also { usage ->
      runCatching { usage.initCause(error) }
    }
  }

  private fun goalContinuationMissingFields(): List<String> = buildList {
    if (goalParentIssueKey.isNullOrBlank()) add("--goal-parent-issue-key is")
    if (goalSubtaskId == null) add("--goal-subtask-id is")
    if (goalBranch.isNullOrBlank()) add("--goal-branch is")
    if (goalReviewBaseSha.isNullOrBlank()) add("--goal-review-base-sha is")
    if (!suppressPr) add("--suppress-pr is")
  }
}

@Inject
@Suppress("LongParameterList")
class FeatureTaskRuntimeRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
  featureTaskRuntimeExplicitRunCommand: FeatureTaskRuntimeExplicitRunCommand,
  featureTaskRuntimeStatusCommand: FeatureTaskRuntimeStatusCommand,
  featureTaskRuntimeResumeCommand: FeatureTaskRuntimeResumeCommand,
  featureTaskRuntimeAbandonCommand: FeatureTaskRuntimeAbandonCommand,
  featureTaskRuntimeRetryBlockedCommand: FeatureTaskRuntimeRetryBlockedCommand,
  featureTaskRuntimeRepairIdentityCommand: FeatureTaskRuntimeRepairIdentityCommand,
  featureTaskLookupCommand: FeatureTaskLookupCommand,
  rejectedOutputInspectCliCommand: RejectedOutputInspectCliCommand,
  rejectedOutputCleanupCliCommand: RejectedOutputCleanupCliCommand,
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
      featureTaskRuntimeStatusCommand,
      featureTaskRuntimeResumeCommand,
      featureTaskRuntimeAbandonCommand,
      featureTaskRuntimeRetryBlockedCommand,
      featureTaskRuntimeRepairIdentityCommand,
      featureTaskLookupCommand,
      rejectedOutputInspectCliCommand,
      rejectedOutputCleanupCliCommand,
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
      workflowId = { resolveRunWorkflowId(workflowService, deps.state, runIssueKey, runSpecPath, repoRoot ?: ".") },
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
      workflowId = { resolveRunWorkflowId(workflowService, deps.state, issueKey, runSpecPath, repoRoot ?: ".") },
    )
  }
}
