package skillbill.cli.workflow

import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.cli.kernel.CliOutput
import skillbill.cli.kernel.CliRunState
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.GoalObservabilityEventValidator

/**
 * SKILL-52.1 — Adapter-side mappers that convert typed
 * `WorkflowService` results into the wire-shape `LinkedHashMap`
 * payloads consumed by [CliRunState.complete] / [CliOutput].
 *
 * Each mapper preserves the EXACT key order produced by the prior
 * `WorkflowContracts.*` serializers. Goldens locking the wire shape:
 *
 *  - `runtime-cli/src/test/resources/golden/cli-verify-workflow-show.json`
 *
 * Any field-order change here will break those goldens; update the
 * goldens deliberately rather than reordering the mapper.
 */
internal fun WorkflowOpenResult.toCliMap(
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?> = when (this) {
  is WorkflowOpenResult.Ok -> workflowSnapshotCliMap(snapshot, goalObservabilityEventValidator).apply {
    launchProjection?.let { put("launch_projection", WorkflowEngine.inputProjectionMap(it)) }
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowOpenResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
  )
}

internal fun WorkflowGetResult.toCliMap(
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?> = when (this) {
  is WorkflowGetResult.Ok -> workflowSnapshotCliMap(snapshot, goalObservabilityEventValidator).apply {
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowGetResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
    "db_path" to dbPath,
  )
}

internal fun WorkflowListResult.toCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "db_path" to dbPath,
  "workflow_count" to workflowCount,
  "workflows" to workflows.map(WorkflowEngine::summaryMap),
)

internal fun WorkflowLatestResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowLatestResult.Ok -> LinkedHashMap(WorkflowEngine.summaryMap(summary)).apply {
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowLatestResult.Error -> linkedMapOf(
    "status" to "error",
    "error" to error,
    "db_path" to dbPath,
  )
}

internal fun WorkflowResumeResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowResumeResult.Ok -> LinkedHashMap(WorkflowEngine.resumeMap(resume)).apply {
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowResumeResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
    "db_path" to dbPath,
  )
}
