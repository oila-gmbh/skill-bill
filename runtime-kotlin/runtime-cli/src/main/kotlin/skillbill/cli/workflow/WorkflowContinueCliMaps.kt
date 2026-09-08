package skillbill.cli.workflow

import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowContinueView

internal fun standardContinueMap(
  view: WorkflowContinueView,
  dbPath: String,
  decompositionExtras: Map<String, Any?>,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowEngine.compactContinueMap(view.compact))
  val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
  val quotedWorkflowId = "'${view.resume.snapshot.workflowId.replace("'", "'\"'\"'")}'"
  map["read_only_full_state_command"] =
    "skill-bill --db $quotedDbPath verify-workflow show $quotedWorkflowId --format json"
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
