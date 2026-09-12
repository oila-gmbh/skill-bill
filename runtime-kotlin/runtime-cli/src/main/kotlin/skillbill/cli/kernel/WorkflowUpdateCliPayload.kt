package skillbill.cli.kernel

import skillbill.application.workflow.WorkflowWireProjections
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.SharedPayloadKeys

/**
 * Wire shape for every workflow-mutating CLI command, whichever command area owns it. The key order
 * matches the prior `WorkflowContracts.*` serializers; the golden
 * `runtime-cli/src/test/resources/golden/cli-verify-workflow-show.json` locks it.
 */
internal fun WorkflowUpdateResult.toPayload(): Map<String, Any?> = when (this) {
  is WorkflowUpdateResult.Ok -> LinkedHashMap(WorkflowWireProjections.updateAcknowledgementMap(acknowledgement)).apply {
    launchProjection?.let { put("launch_projection", WorkflowWireProjections.inputProjectionMap(it)) }
    val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
    val quotedWorkflowId = "'${acknowledgement.workflowId.replace("'", "'\"'\"'")}'"
    put(
      "read_only_full_state_command",
      "skill-bill --db $quotedDbPath verify-workflow show $quotedWorkflowId --format json",
    )
    put("db_path", dbPath)
  }
  is WorkflowUpdateResult.Error -> linkedMapOf<String, Any?>(
    SharedPayloadKeys.STATUS to "error",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "error" to error,
  ).apply { dbPath?.let { put("db_path", it) } }
}
