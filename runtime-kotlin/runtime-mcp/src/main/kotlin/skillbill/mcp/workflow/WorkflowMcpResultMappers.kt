package skillbill.mcp.workflow

import skillbill.application.workflow.WorkflowWireProjections
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.goal.GoalObservabilityEventValidator

/**
 * SKILL-52.1 — Adapter-side mappers that convert typed
 * [WorkflowService][skillbill.application.WorkflowService] results
 * into the wire-shape `LinkedHashMap` payloads consumed by the MCP
 * envelope. Goldens locking this wire shape:
 *
 *  - `runtime-mcp/src/test/resources/golden/mcp-feature-task-runtime-workflow.json`
 *  - `runtime-mcp/src/test/resources/golden/mcp-feature-verify-workflow.json`
 *
 * Mirror of `runtime-cli/.../WorkflowCliResultMappers.kt`. The MCP and
 * CLI mappers share the SAME wire shape, so changes here must be
 * mirrored in the CLI mapper (and both goldens regenerated
 * deliberately).
 */
internal fun WorkflowOpenResult.toMcpMap(
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?> = when (this) {
  is WorkflowOpenResult.Ok -> workflowSnapshotMcpMap(snapshot, goalObservabilityEventValidator).apply {
    launchProjection?.let { put("launch_projection", WorkflowWireProjections.inputProjectionMap(it)) }
    put(SharedPayloadKeys.STATUS, "ok")
    put("db_path", dbPath)
  }
  is WorkflowOpenResult.Error -> linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "error" to error,
  )
}

internal fun WorkflowUpdateResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowUpdateResult.Ok -> LinkedHashMap(WorkflowWireProjections.updateAcknowledgementMap(acknowledgement)).apply {
    launchProjection?.let { put("launch_projection", WorkflowWireProjections.inputProjectionMap(it)) }
    val workflowCommand = if (acknowledgement.workflowName == "bill-feature-verify") "verify-workflow" else "workflow"
    val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
    val quotedWorkflowId = "'${acknowledgement.workflowId.replace("'", "'\"'\"'")}'"
    put(
      "read_only_full_state_command",
      "skill-bill --db $quotedDbPath $workflowCommand show $quotedWorkflowId --format json",
    )
    put("db_path", dbPath)
  }
  is WorkflowUpdateResult.Error -> linkedMapOf<String, Any?>(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "error" to error,
  ).apply { dbPath?.let { put("db_path", it) } }
}

internal fun WorkflowGetResult.toMcpMap(
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?> = when (this) {
  is WorkflowGetResult.Ok -> workflowSnapshotMcpMap(snapshot, goalObservabilityEventValidator).apply {
    put(SharedPayloadKeys.STATUS, "ok")
    put("db_path", dbPath)
  }
  is WorkflowGetResult.Error -> linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "error" to error,
    "db_path" to dbPath,
  )
}

internal fun WorkflowListResult.toMcpMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.STATUS to "ok",
  "db_path" to dbPath,
  "workflow_count" to workflowCount,
  "workflows" to workflows.map(WorkflowWireProjections::summaryMap),
)

internal fun WorkflowLatestResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowLatestResult.Ok -> LinkedHashMap(WorkflowWireProjections.summaryMap(summary)).apply {
    put(SharedPayloadKeys.STATUS, "ok")
    put("db_path", dbPath)
  }
  is WorkflowLatestResult.Error -> linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    "error" to error,
    "db_path" to dbPath,
  )
}

internal fun WorkflowResumeResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowResumeResult.Ok -> LinkedHashMap(WorkflowWireProjections.resumeMap(resume)).apply {
    put(SharedPayloadKeys.STATUS, "ok")
    put("db_path", dbPath)
  }
  is WorkflowResumeResult.Error -> linkedMapOf(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "error" to error,
    "db_path" to dbPath,
  )
}
