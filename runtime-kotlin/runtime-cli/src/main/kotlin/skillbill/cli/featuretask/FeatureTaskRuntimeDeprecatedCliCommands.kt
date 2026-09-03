package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.workflow.WorkflowService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.ports.featuretask.model.FeatureTaskRouteScope

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
    deps.inputs.liveStderr(FEATURE_TASK_RUNTIME_DEPRECATION_NOTE)
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task run.")
    val runSpecPath = resolveSpecPath(deps, runIssueKey, specPath)
    executeRuntimeRun(
      deps = deps,
      issueKey = runIssueKey,
      specPath = runSpecPath,
      workflowId = {
        workflowService.openRuntimeWorkflowId(
          deps.inputs,
          runIssueKey,
          runSpecPath,
          repoRoot ?: ".",
          if (goalParentIssueKey != null) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE,
        )
      },
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
    val runSpecPath = resolveSpecPath(deps, issueKey, specPath)
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = runSpecPath,
      workflowId = {
        workflowService.openRuntimeWorkflowId(
          deps.inputs,
          issueKey,
          runSpecPath,
          repoRoot ?: ".",
          if (goalParentIssueKey != null) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE,
        )
      },
    )
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("status", "Show read-only feature-task phase status.") {
  private val workflowId by argument(help = "Runtime workflow id whose phase status to show.")

  override fun run() {
    val projection = statusService.status(
      FeatureTaskRuntimeStatusRequest(workflowId = workflowId, dbPathOverride = inputs.dbPathOverride),
    )
    val payload = projection.toRuntimeStatusCliMap(workflowId)
    state.completeText(runtimeStatusText(payload), payload, exitCode = payload.runtimeStatusExitCode())
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedResumeCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val lookupService: FeatureTaskContinuationLookupService,
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
      workflowId = {
        verifyRuntimeResume(
          VerifyRuntimeResumeArgs(
            lookupService = lookupService,
            inputs = deps.inputs,
            workflowId = workflowId,
            issueKey = issueKey,
            specPath = specPath,
            repoRoot = repoRoot ?: ".",
            goalChild = goalParentIssueKey != null,
          ),
        )
        workflowId
      },
    )
  }
}
