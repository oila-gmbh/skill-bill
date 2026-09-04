package skillbill.cli.workflow

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowLatestResult.Error
import skillbill.application.workflow.model.WorkflowLatestResult.Ok
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.DocumentedNoOpCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.kernel.toPayload
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.model.FeatureTaskRouteScope

@Inject
class WorkflowTopLevelCommands(
  verifyCommands: VerifyWorkflowCommands,
) {
  val verifyWorkflowCommand: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "verify-workflow",
      "Inspect or resume durable bill-feature-verify workflow runs.",
    ) {}
      .subcommands(
        verifyCommands.open,
        verifyCommands.update,
        verifyCommands.show,
        verifyCommands.get,
        verifyCommands.list,
        verifyCommands.latest,
        verifyCommands.resume,
        verifyCommands.continueCommand,
      )

  val commands: List<CliktCommand> = listOf(verifyWorkflowCommand)
}

@Inject
class VerifyWorkflowCommands(
  verifyOpen: VerifyWorkflowOpenCommand,
  verifyUpdate: VerifyWorkflowUpdateCommand,
  verifyGet: VerifyWorkflowGetCommand,
  verifyInspection: VerifyWorkflowInspectionCommands,
  verifyResume: VerifyWorkflowResumeCommand,
  verifyContinue: VerifyWorkflowContinueCommand,
) {
  val open = verifyOpen
  val update = verifyUpdate
  val show = verifyInspection.show
  val get = verifyGet
  val list = verifyInspection.list
  val latest = verifyInspection.latest
  val resume = verifyResume
  val continueCommand = verifyContinue
}

@Inject
class VerifyWorkflowInspectionCommands(
  val show: VerifyWorkflowShowCommand,
  val list: VerifyWorkflowListCommand,
  val latest: VerifyWorkflowLatestCommand,
)

@Inject
class VerifyWorkflowOpenCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowOpenCommand("open", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowOpenCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Open durable workflow state.") {
  private val sessionId by option("--session-id", help = "Optional workflow telemetry session id.").default("")
  private val currentStepId by option("--current-step-id", help = "Initial workflow step id.")
  private val issueKey by option("--issue-key", help = "Optional normalized issue key for work inventory.")
  private val format by formatOption()

  override fun run() {
    val opened =
      service.open(
        WorkflowServiceOpenArgs(
          kind = kind,
          sessionId = sessionId,
          currentStepId = currentStepId,
          dbOverride = inputs.dbPathOverride,
          issueKey = issueKey,
          repositoryIdentity = null,
          governedSpecPath = null,
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    val payload =
      opened
        .toCliMap(service.goalObservabilityEventValidator)
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowUpdateCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowUpdateCommand("update", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowUpdateCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Update durable workflow state and return a compact acknowledgement.") {
  private val workflowId by argument(help = "Workflow id to update.")
  private val workflowStatus by option("--workflow-status", help = "Next workflow status.").required()
  private val currentStepId by option("--current-step-id", help = "Optional current step id.").default("")
  private val stepUpdates by option("--step-updates", help = "JSON array of step updates.")
  private val artifactsPatch by option("--artifacts-patch", help = "JSON object of artifacts to merge.")
  private val sessionId by option("--session-id", help = "Optional replacement session id.").default("")
  private val format by formatOption()

  override fun run() {
    val request =
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        stepUpdates = stepUpdates?.let(::parseStepUpdates),
        artifactsPatch = artifactsPatch?.let(::parseArtifactsPatch),
        sessionId = sessionId,
      )
    val payload =
      service.update(kind, request, inputs.dbPathOverride).toPayload()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowShowCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowGetCommand("show", service, state, inputs, WorkflowFamilyKind.VERIFY)

@Inject
class VerifyWorkflowGetCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowGetCommand("get", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowGetCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Fetch read-only full durable workflow state.") {
  private val workflowId by argument(help = "Workflow id to inspect.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, inputs, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.get(kind, requireNotNull(resolution.workflowId), inputs.dbPathOverride)
          .toCliMap(service.goalObservabilityEventValidator)
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowListCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowListCommand("list", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowListCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "List recent persisted workflow runs.") {
  private val limit by option("--limit", help = "Maximum number of workflows to return.").int()
    .default(DEFAULT_WORKFLOW_LIST_LIMIT)
  private val format by formatOption()

  override fun run() {
    val payload =
      service.list(kind, limit, inputs.dbPathOverride).toCliMap()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowLatestCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowLatestCommand("latest", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowLatestCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Fetch the most recently updated workflow run.") {
  private val format by formatOption()

  override fun run() {
    val payload =
      service.latest(kind, inputs.dbPathOverride).toCliMap()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowResumeCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowResumeCommand("resume", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowResumeCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Summarize how to resume or recover a workflow run.") {
  private val workflowId by argument(help = "Workflow id to resume or recover.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, inputs, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.resume(kind, requireNotNull(resolution.workflowId), inputs.dbPathOverride).toCliMap()
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowContinueCommand(
  service: WorkflowService,
  state: CliRunState,
  inputs: CliRunInputs,
) : WorkflowContinueCommand("continue", service, state, inputs, WorkflowFamilyKind.VERIFY)

open class WorkflowContinueCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Activate a resumable workflow and emit a recovered continuation brief.") {
  private val workflowId by argument(
    help = "Workflow id to continue, or an issue key for a decomposed feature parent.",
  ).optional()
  private val subtaskId by option(
    "--subtask-id",
    help = "Optional decomposed parent subtask id constraint for issue-key continuation.",
  ).int()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, inputs, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.continueWorkflow(
          kind,
          requireNotNull(resolution.workflowId),
          subtaskId = subtaskId,
          dbOverride = inputs.dbPathOverride,
        ).toCliMap()
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

private fun parseStepUpdates(rawValue: String): List<Map<String, Any?>> =
  JsonSupport.parseArrayOrEmpty(rawValue).mapIndexed { index, value ->
    val update = JsonSupport.anyToStringAnyMap(value)
    require(update != null) { "step_updates[$index] must be an object." }
    update
  }

private fun parseArtifactsPatch(rawValue: String): Map<String, Any?> = JsonSupport.parseObjectOrNull(rawValue)
  ?.let(JsonSupport::jsonElementToValue)
  ?.let(JsonSupport::anyToStringAnyMap)
  ?: run {
    require(false) { "artifacts_patch must be an object." }
    emptyMap()
  }

private fun Map<String, Any?>.exitCode(): Int = if (this["status"] == "error") 1 else 0

private fun resolveWorkflowId(
  workflowId: String?,
  latest: Boolean,
  service: WorkflowService,
  inputs: CliRunInputs,
  kind: WorkflowFamilyKind,
): WorkflowIdResolution {
  workflowId?.let { return WorkflowIdResolution(workflowId = it) }
  require(latest) { "Provide a workflow_id or pass --latest." }
  return when (val latestResult = service.latest(kind, inputs.dbPathOverride)) {
    is Ok ->
      WorkflowIdResolution(workflowId = latestResult.summary.workflowId)
    is Error ->
      WorkflowIdResolution(workflowId = null, errorPayload = latestResult.toCliMap())
  }
}

private const val DEFAULT_WORKFLOW_LIST_LIMIT: Int = 20

private data class WorkflowIdResolution(
  val workflowId: String?,
  val errorPayload: Map<String, Any?>? = null,
)
