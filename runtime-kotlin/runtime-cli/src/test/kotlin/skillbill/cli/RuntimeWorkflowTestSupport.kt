package skillbill.cli

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.workflow.toCliMap
import skillbill.contracts.JsonSupport
import skillbill.di.RuntimeComponent
import skillbill.di.create
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * Seeds and inspects task-runtime workflow rows through [WorkflowService] so CLI
 * tests can exercise goal/runtime behavior without the removed `skill-bill workflow`
 * command tree.
 */
internal object RuntimeWorkflowTestSupport {
  fun open(dbPath: Path, context: CliRuntimeContext): Map<String, Any?> {
    val service = component(context).workflowService
    val result = service.open(
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      dbOverride = dbPath.toString(),
    )
    return assertIs<WorkflowOpenResult.Ok>(result)
      .toCliMap(service.goalObservabilityEventValidator)
  }

  fun update(
    dbPath: Path,
    workflowId: String,
    workflowStatus: String,
    currentStepId: String,
    stepUpdates: List<Map<String, Any?>>?,
    artifactsPatch: Map<String, Any?>?,
    context: CliRuntimeContext,
  ): Map<String, Any?> {
    val service = component(context).workflowService
    val result = service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        stepUpdates = stepUpdates,
        artifactsPatch = artifactsPatch,
      ),
      dbPath.toString(),
    )
    return assertIs<WorkflowUpdateResult.Ok>(result).toCliMap()
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

  fun parseStepUpdates(rawJson: String): List<Map<String, Any?>> =
    JsonSupport.parseArrayOrEmpty(rawJson).map { value ->
      requireNotNull(JsonSupport.anyToStringAnyMap(value))
    }

  fun parseArtifactsPatch(rawJson: String): Map<String, Any?> =
    requireNotNull(
      JsonSupport.parseObjectOrNull(rawJson)
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap),
    )

  private fun component(context: CliRuntimeContext): RuntimeComponent =
    RuntimeComponent::class.create(context.toRuntimeContext())
}
