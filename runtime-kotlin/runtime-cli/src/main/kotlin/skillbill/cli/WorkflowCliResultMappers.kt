package skillbill.cli

import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowLatestResult
import skillbill.application.model.WorkflowListResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowResumeResult
import skillbill.application.model.WorkflowUpdateResult
import skillbill.workflow.WorkflowEngine

/**
 * SKILL-52.1 — Adapter-side mappers that convert typed
 * `WorkflowService` results into the wire-shape `LinkedHashMap`
 * payloads consumed by [CliRunState.complete] / [CliOutput].
 *
 * Each mapper preserves the EXACT key order produced by the prior
 * `WorkflowContracts.*` serializers. Goldens locking the wire shape:
 *
 *  - `runtime-cli/src/test/resources/golden/cli-workflow-show.json`
 *  - `runtime-cli/src/test/resources/golden/cli-verify-workflow-show.json`
 *
 * Any field-order change here will break those goldens; update the
 * goldens deliberately rather than reordering the mapper.
 */
internal fun WorkflowOpenResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowOpenResult.Ok -> LinkedHashMap(WorkflowEngine.snapshotMap(snapshot)).apply {
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowOpenResult.Error -> linkedMapOf(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
  )
}

internal fun WorkflowUpdateResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowUpdateResult.Ok -> LinkedHashMap(WorkflowEngine.snapshotMap(snapshot)).apply {
    put("status", "ok")
    put("db_path", dbPath)
  }
  is WorkflowUpdateResult.Error -> linkedMapOf<String, Any?>(
    "status" to "error",
    "workflow_id" to workflowId,
    "error" to error,
  ).apply { dbPath?.let { put("db_path", it) } }
}

internal fun WorkflowGetResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowGetResult.Ok -> LinkedHashMap(WorkflowEngine.snapshotMap(snapshot)).apply {
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

@Suppress("LongMethod") // each branch is a flat wire-shape map; extracting helpers would obscure
internal fun WorkflowContinueResult.toCliMap(): Map<String, Any?> = when (this) {
  is WorkflowContinueResult.Standard -> standardContinueMap(view, dbPath, decompositionExtras = emptyMap())
  is WorkflowContinueResult.DecompositionStandard -> standardContinueMap(
    view = view,
    dbPath = dbPath,
    decompositionExtras = linkedMapOf(
      "decomposition_subtask_id" to decompositionSubtaskId,
      "decomposition_subtask_spec_path" to decompositionSubtaskSpecPath,
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

private fun standardContinueMap(
  view: skillbill.workflow.model.WorkflowContinueView,
  dbPath: String,
  decompositionExtras: Map<String, Any?>,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowEngine.continueMap(view))
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
