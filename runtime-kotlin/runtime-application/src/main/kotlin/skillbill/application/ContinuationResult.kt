package skillbill.application

import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

internal data class ContinuationResult(
  val payload: Map<String, Any?>,
  val projectionArtifactsJson: String? = null,
) {
  fun withProjection(manifest: DecompositionManifest): ContinuationResult =
    copy(projectionArtifactsJson = decompositionRuntimeArtifactsJson(manifest))

  fun withProjectionArtifactsIfMissing(artifactsJson: String?): ContinuationResult =
    if (projectionArtifactsJson == null && artifactsJson != null) {
      copy(projectionArtifactsJson = artifactsJson)
    } else {
      this
    }

  fun withDecompositionFields(subtaskId: Int, specPath: String): ContinuationResult = copy(
    payload = LinkedHashMap(payload).apply {
      put("decomposition_subtask_id", subtaskId)
      put("decomposition_subtask_spec_path", specPath)
    },
  )
}

internal fun missingSubtaskWorkflowPayload(
  selection: DecompositionContinuationSelection.Resume,
  unitOfWork: UnitOfWork,
): ContinuationResult = ContinuationResult(
  linkedMapOf(
    "status" to "error",
    "continue_status" to "blocked",
    "subtask_id" to selection.subtask.id,
    "blocked_reason" to "Subtask ${selection.subtask.id} is in progress but has no workflow_id.",
    "db_path" to unitOfWork.dbPath.toString(),
  ),
)

internal fun blockedSubtaskPayload(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  selection: DecompositionContinuationSelection.Blocked,
  dbPath: String,
): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "workflow_id" to parentRecord.workflowId,
  "issue_key" to manifest.issueKey,
  "decomposition_subtask_id" to selection.subtask.id,
  "decomposition_subtask_spec_path" to selection.subtask.specPath,
  "blocked_reason" to selection.reason,
  "error" to selection.reason,
  "db_path" to dbPath,
)

internal fun doneDecompositionPayload(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  dbPath: String,
): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "continue_status" to "done",
  "workflow_id" to parentRecord.workflowId,
  "issue_key" to manifest.issueKey,
  "decomposition_status" to manifest.status,
  "db_path" to dbPath,
)

internal fun gitErrorPayload(
  parentWorkflowId: String,
  issueKey: String,
  error: String,
  unitOfWork: UnitOfWork,
): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "workflow_id" to parentWorkflowId,
  "issue_key" to issueKey,
  "error" to error.ifBlank { "Git operation failed." },
  "db_path" to unitOfWork.dbPath.toString(),
)

internal fun blockedGitPayload(
  parentWorkflowId: String,
  issueKey: String,
  dbPath: String,
  reason: String,
): Map<String, Any?> = linkedMapOf(
  "status" to "error",
  "continue_status" to "blocked",
  "workflow_id" to parentWorkflowId,
  "issue_key" to issueKey,
  "blocked_reason" to reason.ifBlank { "Subtask advancement failed." },
  "error" to reason.ifBlank { "Subtask advancement failed." },
  "db_path" to dbPath,
)

internal fun decompositionRuntimeArtifactsJson(manifest: DecompositionManifest): String = jsonString(
  mapOf(
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
      manifest,
      DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
    ),
  ),
)

private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  kotlinx.serialization.json.JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)
