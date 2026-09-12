package skillbill.mcp.workflow

import skillbill.contracts.SharedPayloadKeys

import skillbill.application.workflow.WorkflowWireProjections
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.model.WorkflowContinueStatus

internal fun standardMcpContinueMap(
  view: WorkflowContinueView,
  dbPath: String,
  decompositionExtras: Map<String, Any?>,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowWireProjections.compactContinueMap(view.compact))
  val workflowCommand = if (view.skillName == "bill-feature-verify") "verify-workflow" else "workflow"
  val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
  val quotedWorkflowId = "'${view.resume.snapshot.workflowId.replace("'", "'\"'\"'")}'"
  map["read_only_full_state_command"] =
    "skill-bill --db $quotedDbPath $workflowCommand show $quotedWorkflowId --format json"
  decompositionExtras.forEach { (key, value) -> map[key] = value }
  map["db_path"] = dbPath
  if (view.continueStatus == WorkflowContinueStatus.BLOCKED) {
    val missingArtifacts = view.resume.missingArtifacts
    map[SharedPayloadKeys.STATUS] = "error"
    map["error"] =
      "Cannot continue workflow until the missing artifacts are restored: " +
      missingArtifacts.joinToString()
  } else {
    map[SharedPayloadKeys.STATUS] = "ok"
  }
  return map
}
