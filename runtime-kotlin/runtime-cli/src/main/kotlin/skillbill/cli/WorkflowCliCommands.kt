package skillbill.cli

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
import skillbill.application.WorkflowService
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.contracts.JsonSupport

@Inject
class WorkflowTopLevelCommands(
  implementCommands: ImplementWorkflowCommands,
  verifyCommands: VerifyWorkflowCommands,
) {
  val workflowCommand: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand("workflow", "Inspect or resume durable bill-feature-implement workflow runs.") {}
      .subcommands(
        implementCommands.open,
        implementCommands.update,
        implementCommands.show,
        implementCommands.get,
        implementCommands.list,
        implementCommands.latest,
        implementCommands.resume,
        implementCommands.continueCommand,
      )
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

  val commands: List<CliktCommand> = listOf(workflowCommand, verifyWorkflowCommand)
}

@Inject
class ImplementWorkflowCommands(
  implementOpen: ImplementWorkflowOpenCommand,
  implementUpdate: ImplementWorkflowUpdateCommand,
  implementGet: ImplementWorkflowGetCommand,
  implementInspection: ImplementWorkflowInspectionCommands,
  implementResume: ImplementWorkflowResumeCommand,
  implementContinue: ImplementWorkflowContinueCommand,
) {
  val open = implementOpen
  val update = implementUpdate
  val show = implementInspection.show
  val get = implementGet
  val list = implementInspection.list
  val latest = implementInspection.latest
  val resume = implementResume
  val continueCommand = implementContinue
}

@Inject
class ImplementWorkflowInspectionCommands(
  val show: ImplementWorkflowShowCommand,
  val list: ImplementWorkflowListCommand,
  val latest: ImplementWorkflowLatestCommand,
)

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
class ImplementWorkflowOpenCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowOpenCommand("open", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowOpenCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowOpenCommand("open", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowOpenCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Open durable workflow state.") {
  private val sessionId by option("--session-id", help = "Optional workflow telemetry session id.").default("")
  private val currentStepId by option("--current-step-id", help = "Initial workflow step id.")
  private val format by formatOption()

  override fun run() {
    val payload =
      service.open(kind, sessionId, currentStepId, state.dbOverride)
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowUpdateCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowUpdateCommand("update", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowUpdateCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowUpdateCommand("update", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowUpdateCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Update durable workflow state.") {
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
      service.update(kind, request, state.dbOverride)
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowShowCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("show", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowShowCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("show", service, state, WorkflowFamilyKind.VERIFY)

@Inject
class ImplementWorkflowGetCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("get", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowGetCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("get", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowGetCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Fetch durable workflow state.") {
  private val workflowId by argument(help = "Workflow id to inspect.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, state, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.get(kind, requireNotNull(resolution.workflowId), state.dbOverride)
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowListCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowListCommand("list", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowListCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowListCommand("list", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowListCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "List recent persisted workflow runs.") {
  private val limit by option("--limit", help = "Maximum number of workflows to return.").int()
    .default(DEFAULT_WORKFLOW_LIST_LIMIT)
  private val format by formatOption()

  override fun run() {
    val payload =
      service.list(kind, limit, state.dbOverride)
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowLatestCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowLatestCommand("latest", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowLatestCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowLatestCommand("latest", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowLatestCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Fetch the most recently updated workflow run.") {
  private val format by formatOption()

  override fun run() {
    val payload =
      service.latest(kind, state.dbOverride)
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowResumeCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowResumeCommand("resume", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowResumeCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowResumeCommand("resume", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowResumeCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Summarize how to resume or recover a workflow run.") {
  private val workflowId by argument(help = "Workflow id to resume or recover.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, state, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.resume(kind, requireNotNull(resolution.workflowId), state.dbOverride)
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class ImplementWorkflowContinueCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowContinueCommand("continue", service, state, WorkflowFamilyKind.IMPLEMENT)

@Inject
class VerifyWorkflowContinueCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowContinueCommand("continue", service, state, WorkflowFamilyKind.VERIFY)

open class WorkflowContinueCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Activate a resumable workflow and emit a recovered continuation brief.") {
  private val workflowId by argument(help = "Workflow id to continue.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, state, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.continueWorkflow(kind, requireNotNull(resolution.workflowId), state.dbOverride)
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
  state: CliRunState,
  kind: WorkflowFamilyKind,
): WorkflowIdResolution {
  workflowId?.let { return WorkflowIdResolution(workflowId = it) }
  require(latest) { "Provide a workflow_id or pass --latest." }
  val latestPayload =
    when (kind) {
      WorkflowFamilyKind.IMPLEMENT -> service.latest(WorkflowFamilyKind.IMPLEMENT, state.dbOverride)
      WorkflowFamilyKind.VERIFY -> service.latest(WorkflowFamilyKind.VERIFY, state.dbOverride)
    }
  return WorkflowIdResolution(
    workflowId = latestPayload["workflow_id"] as? String,
    errorPayload = latestPayload.takeIf { it["status"] == "error" },
  )
}

private const val DEFAULT_WORKFLOW_LIST_LIMIT: Int = 20

private data class WorkflowIdResolution(
  val workflowId: String?,
  val errorPayload: Map<String, Any?>? = null,
)
