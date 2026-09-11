package skillbill.infrastructure.fs

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.MalformedJsonTextError
import skillbill.workflow.engine.model.WorkflowStateSnapshot

object WorkflowStateSnapshotWireMapper {
  @OpenBoundaryMap("Canonical workflow-state snapshot map at the schema-validation seam")
  fun wireMap(snapshot: WorkflowStateSnapshot): Map<String, Any?> = linkedMapOf<String, Any?>(
    "workflow_id" to snapshot.workflowId,
    "session_id" to snapshot.sessionId.orEmpty(),
    "workflow_name" to snapshot.workflowName,
    "contract_version" to snapshot.contractVersion,
    "workflow_status" to snapshot.workflowStatus,
    "current_step_id" to snapshot.currentStepId.orEmpty(),
    "steps" to decodeArray(snapshot.stepsJson, "stepsJson"),
    "artifacts" to decodeMap(snapshot.artifactsJson, "artifactsJson"),
    "started_at" to snapshot.startedAt.orEmpty(),
    "updated_at" to snapshot.updatedAt.orEmpty(),
    "finished_at" to snapshot.finishedAt.orEmpty(),
  ).apply {
    snapshot.mode?.let { mode -> put("mode", mode) }
  }

  private fun decodeArray(rawValue: String, field: String): List<Map<String, Any?>> {
    val parsed = parse(rawValue, field) as? List<*>
      ?: throw InvalidWorkflowStateSchemaError("Workflow state $field must decode to a JSON array.")
    return parsed.mapIndexed { index, entry ->
      JsonCodec.anyToStringAnyMap(entry)
        ?: throw InvalidWorkflowStateSchemaError("Workflow state $field[$index] must decode to a JSON object.")
    }
  }

  private fun decodeMap(rawValue: String, field: String): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(parse(rawValue, field))
      ?: throw InvalidWorkflowStateSchemaError("Workflow state $field must decode to a JSON object.")

  private fun parse(rawValue: String, field: String): Any? = try {
    JsonCodec.parseValue(rawValue)
  } catch (error: MalformedJsonTextError) {
    throw InvalidWorkflowStateSchemaError("Workflow state $field contains malformed JSON.", error)
  }
}
