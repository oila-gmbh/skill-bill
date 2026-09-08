package skillbill.cli

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.cli.kernel.toPayload
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.workflow.toCliMap
import skillbill.contracts.JsonCodec
import skillbill.di.RuntimeComponent
import skillbill.di.create
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertIs

internal fun installFakeRuntimeMcpBin(home: Path): Path {
  val bin = home.resolve(".skill-bill").resolve("runtime").resolve("runtime-mcp").resolve("bin").resolve("runtime-mcp")
  Files.createDirectories(bin.parent)
  Files.writeString(bin, "#!/bin/sh\nexit 0\n")
  Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"))
  return bin
}

internal object RuntimeWorkflowTestSupport {
  fun open(dbPath: Path, context: CliRuntimeContext): Map<String, Any?> {
    val service = component(context).workflowService
    val result = service.open(
      WorkflowServiceOpenArgs(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        dbOverride = dbPath.toString(),
      ),
    )
    return assertIs<WorkflowOpenResult.Ok>(result)
      .toCliMap(service.goalObservabilityEventValidator)
  }

  data class UpdateArgs(
    val dbPath: Path,
    val workflowId: String,
    val workflowStatus: String,
    val currentStepId: String,
    val stepUpdates: List<Map<String, Any?>>?,
    val artifactsPatch: Map<String, Any?>?,
    val context: CliRuntimeContext,
  )

  fun update(args: UpdateArgs): Map<String, Any?> {
    val service = component(args.context).workflowService
    val result = service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = args.workflowId,
        workflowStatus = args.workflowStatus,
        currentStepId = args.currentStepId,
        stepUpdates = args.stepUpdates,
        artifactsPatch = args.artifactsPatch,
      ),
      args.dbPath.toString(),
    )
    return assertIs<WorkflowUpdateResult.Ok>(result).toPayload()
  }

  fun get(dbPath: Path, workflowId: String, context: CliRuntimeContext): Map<String, Any?> {
    val service = component(context).workflowService
    val result = service.get(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbPath.toString())
    return assertIs<WorkflowGetResult.Ok>(result).toCliMap(service.goalObservabilityEventValidator)
  }

  fun continueByIssueKey(
    dbPath: Path,
    issueKey: String,
    subtaskId: Int?,
    context: CliRuntimeContext,
  ): Map<String, Any?> {
    val service = component(context).workflowService
    return service.continueWorkflow(
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      workflowId = issueKey,
      subtaskId = subtaskId,
      dbOverride = dbPath.toString(),
    ).toCliMap()
  }

  fun parseStepUpdates(rawJson: String): List<Map<String, Any?>> = JsonCodec.parseArrayOrEmpty(rawJson).map { value ->
    requireNotNull(JsonCodec.anyToStringAnyMap(value))
  }

  fun parseArtifactsPatch(rawJson: String): Map<String, Any?> = requireNotNull(
    JsonCodec.parseObjectOrNull(rawJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap),
  )

  private fun component(context: CliRuntimeContext): RuntimeComponent =
    RuntimeComponent::class.create(context.toRuntimeContext())
}
