package skillbill.mcp.workflow

import skillbill.application.model.GoalContinuationOutcome
import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowLatestResult
import skillbill.application.model.WorkflowListResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowResumeResult
import skillbill.application.model.WorkflowUpdateResult
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.WorkflowEngine

/**
 * SKILL-52.1 — Adapter-side mappers that convert typed
 * [WorkflowService][skillbill.application.WorkflowService] results
 * into the wire-shape `LinkedHashMap` payloads consumed by the MCP
 * envelope. Goldens locking this wire shape:
 *
 *  - `runtime-mcp/src/test/resources/golden/mcp-feature-task-prose-workflow.json`
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
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowOpenResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
  )
}

internal fun WorkflowUpdateResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowUpdateResult.Ok -> LinkedHashMap(WorkflowEngine.updateAcknowledgementMap(acknowledgement)).apply {
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
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
  ).apply { dbPath?.let { put("db_path", it) } }
}

internal fun WorkflowGetResult.toMcpMap(
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?> = when (this) {
  is WorkflowGetResult.Ok -> workflowSnapshotMcpMap(snapshot, goalObservabilityEventValidator).apply {
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

internal fun WorkflowListResult.toMcpMap(): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "db_path" to dbPath,
  "workflow_count" to workflowCount,
  "workflows" to workflows.map(WorkflowEngine::summaryMap),
)

internal fun WorkflowLatestResult.toMcpMap(): Map<String, Any?> = when (this) {
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

internal fun WorkflowResumeResult.toMcpMap(): Map<String, Any?> = when (this) {
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

@Suppress("LongMethod") // each branch is a flat wire-shape map; extracting helpers would obscure
internal fun WorkflowContinueResult.toMcpMap(): Map<String, Any?> = when (this) {
  is WorkflowContinueResult.Standard -> standardMcpContinueMap(view, dbPath, decompositionExtras = emptyMap())
  is WorkflowContinueResult.DecompositionStandard -> standardMcpContinueMap(
    view = view,
    dbPath = dbPath,
    decompositionExtras = linkedMapOf(
      "issue_key" to (outcome?.issueKey ?: issueKey),
      "decomposition_subtask_id" to decompositionSubtaskId,
      "decomposition_subtask_spec_path" to decompositionSubtaskSpecPath,
      "goal_continuation_outcome" to outcome.toWireMap(),
    ),
  )
  is WorkflowContinueResult.UnknownWorkflow -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to "Unknown workflow_id '$workflowId'.",
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow -> linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "subtask_id" to subtaskId,
    "blocked_reason" to blockedReason,
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionBlockedSubtask -> linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "decomposition_subtask_id" to subtaskId,
    "decomposition_subtask_spec_path" to subtaskSpecPath,
    "blocked_reason" to blockedReason,
    "error" to blockedReason,
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionBlockedBranchStart -> linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "error" to blockedReason,
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionDone -> linkedMapOf(
    "status" to "ok",
    "continue_status" to "done",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "decomposition_status" to decompositionStatus,
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionSubtaskOutcome -> linkedMapOf(
    "status" to "ok",
    "continue_status" to "done",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "decomposition_subtask_id" to subtaskId,
    "decomposition_subtask_spec_path" to subtaskSpecPath,
    "goal_continuation_outcome" to outcome.toWireMap(),
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.DecompositionBlockedGit -> linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "workflow_id" to workflowId,
    "issue_key" to issueKey,
    "blocked_reason" to blockedReason,
    "error" to blockedReason,
    "db_path" to dbPath,
  )
  is WorkflowContinueResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
    "db_path" to dbPath,
  )
}

private fun GoalContinuationOutcome?.toWireMap(): Map<String, Any?> = this?.let { outcome ->
  linkedMapOf(
    "issue_key" to outcome.issueKey,
    "subtask_id" to outcome.subtaskId,
    "status" to outcome.status,
    "commit_sha" to outcome.commitSha,
    "workflow_id" to outcome.workflowId,
    "blocked_reason" to outcome.blockedReason,
    "last_resumable_step" to outcome.lastResumableStep,
  )
}.orEmpty()

private fun standardMcpContinueMap(
  view: skillbill.workflow.model.WorkflowContinueView,
  dbPath: String,
  decompositionExtras: Map<String, Any?>,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowEngine.compactContinueMap(view.compact))
  val workflowCommand = if (view.skillName == "bill-feature-verify") "verify-workflow" else "workflow"
  val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
  val quotedWorkflowId = "'${view.resume.snapshot.workflowId.replace("'", "'\"'\"'")}'"
  map["read_only_full_state_command"] =
    "skill-bill --db $quotedDbPath $workflowCommand show $quotedWorkflowId --format json"
  decompositionExtras.forEach { (key, value) -> map[key] = value }
  map["db_path"] = dbPath
  if (view.continueStatus == "blocked") {
    val missingArtifacts = view.resume.missingArtifacts
    map["status"] = "error"
    map["error"] =
      "Cannot continue workflow until the missing artifacts are restored: " +
      missingArtifacts.joinToString()
  } else {
    map["status"] = "ok"
  }
  return map
}
