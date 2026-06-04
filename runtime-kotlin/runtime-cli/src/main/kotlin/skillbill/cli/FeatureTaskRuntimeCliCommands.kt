@file:Suppress("TooManyFunctions")

package skillbill.cli

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
import skillbill.application.FeatureTaskRuntimeRunner
import skillbill.application.FeatureTaskRuntimeStatusService
import skillbill.application.WorkflowService
import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
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
    help = "Agent invoking bill-feature-task-runtime. Resolution order: --agent, then SKILL_BILL_AGENT, then the " +
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

  protected fun executeRuntimeRun(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    specPath: String,
    workflowId: String,
  ) {
    val state = deps.state
    val report = deps.runner.run(
      FeatureTaskRuntimeRunRequest(
        issueKey = issueKey,
        workflowId = workflowId,
        sessionId = "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$issueKey",
        runInvariants = deps.runInvariantsSource.read(Path.of(specPath)),
        invokedAgentId = resolveInvokedRuntimeAgentId(agent, state.environment),
        agentAssignment = FeatureTaskRuntimeAgentAssignment(
          perPhaseAgentIds = parsePhaseAgents(phaseAgents),
          override = agentOverride?.takeIf(String::isNotBlank),
        ),
        environment = state.environment,
        dbPathOverride = state.dbOverride,
        repoRoot = repoRoot?.let(Path::of) ?: Path.of("").toAbsolutePath().normalize(),
        timeout = maxWallClockMinutes?.minutes,
        eventSink = runtimeRunEventSink(state, monitor),
      ),
    )
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(payload), payload, exitCode = payload.runtimeRunExitCode())
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
  "feature-task-runtime",
  "Run the EXPERIMENTAL runtime-driven feature-task phase loop in the foreground. Not a default path.",
) {
  private val issueKey by argument(help = "Issue key the experimental runtime run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(featureTaskRuntimeExplicitRunCommand, featureTaskRuntimeStatusCommand, featureTaskRuntimeResumeCommand)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task-runtime run.")
    val runSpecPath = specPath ?: throw UsageError("spec_path is required for feature-task-runtime run.")
    executeRuntimeRun(
      deps = deps,
      issueKey = runIssueKey,
      specPath = runSpecPath,
      workflowId = openRuntimeWorkflowId(workflowService, deps.state),
    )
  }
}

/**
 * Explicit `run` subcommand mirroring the documented `feature-task-runtime run <issue_key>
 * <spec_path>` form. Without it, clikt silently consumes `run` as the optional issue-key
 * positional of the parent command and misparses the remaining arguments.
 */
@Inject
class FeatureTaskRuntimeExplicitRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
) : FeatureTaskRuntimePhaseAgentCommand(
  "run",
  "Run the EXPERIMENTAL feature-task-runtime phase loop (explicit form of the parent command's default run).",
) {
  private val issueKey by argument(help = "Issue key the experimental runtime run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = specPath,
      workflowId = openRuntimeWorkflowId(workflowService, deps.state),
    )
  }
}

@Inject
class FeatureTaskRuntimeStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only EXPERIMENTAL feature-task-runtime phase status.") {
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
  "Resume an EXPERIMENTAL feature-task-runtime run against an existing workflow id.",
) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed runtime run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = specPath,
      workflowId = workflowId,
    )
  }
}

private fun openRuntimeWorkflowId(workflowService: WorkflowService, state: CliRunState): String =
  when (val opened = workflowService.open(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = state.dbOverride)) {
    is WorkflowOpenResult.Ok -> opened.workflowId
    is WorkflowOpenResult.Error -> throw UsageError(
      "Could not open an experimental feature-task-runtime workflow: ${opened.error}",
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
  is FeatureTaskRuntimeRunEvent.PhaseStarted ->
    "feature-task-runtime $workflowId: phase $phaseId ${if (resumed) "resumed" else "started"} " +
      "agent=$resolvedAgentId attempt=$attemptCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration ->
    "feature-task-runtime $workflowId: phase $phaseId fix_loop attempt=$attemptCount iteration=$fixLoopIteration\n"
  is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
    "feature-task-runtime $workflowId: phase $phaseId completed agent=$resolvedAgentId attempt=$attemptCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
    "feature-task-runtime $workflowId: phase $phaseId blocked attempt=$attemptCount: $blockedReason\n"
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
    "completed_phases" to completedPhaseIds,
  )
  is FeatureTaskRuntimeRunReport.Blocked -> linkedMapOf(
    "status" to "blocked",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "last_incomplete_phase" to lastIncompletePhase,
    "blocked_reason" to blockedReason,
    "completed_phases" to completedPhaseIds,
  )
}

private fun Map<String, Any?>.runtimeRunExitCode(): Int = if (this["status"] == "complete") 0 else 1

private fun runtimeRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["issue_key"]}")
  appendLine("workflow_id: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("completed_phases: ${(payload["completed_phases"] as? List<*>).orEmpty().joinToString()}")
  payload["last_incomplete_phase"]?.let { appendLine("last_incomplete_phase: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
}

private fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      "status" to "ok",
      "workflow_id" to it.workflowId,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_phase" to it.currentPhaseId,
      "phases" to it.phases.map(FeatureTaskRuntimePhaseStatus::toRuntimePhaseStatusCliMap),
    )
  } ?: linkedMapOf(
    "status" to "not_found",
    "workflow_id" to workflowId,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_phase" to null,
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
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_phase: ${payload["current_phase"] ?: "none"}")
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
