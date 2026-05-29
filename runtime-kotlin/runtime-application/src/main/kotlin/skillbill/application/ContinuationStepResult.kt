package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

/**
 * SKILL-52.1 — Internal continuation-step result. Wraps a typed
 * [WorkflowContinueResult] plus the optional projection artifacts JSON
 * that the application persists to disk after the transaction
 * commits.
 */
internal data class ContinuationStepResult(
  val result: WorkflowContinueResult,
  val projectionArtifactsJson: String? = null,
) {
  fun withProjection(
    manifest: DecompositionManifest,
    validator: DecompositionManifestValidator,
  ): ContinuationStepResult = copy(projectionArtifactsJson = decompositionRuntimeArtifactsJson(manifest, validator))

  fun withProjectionArtifactsIfMissing(artifactsJson: String?): ContinuationStepResult =
    if (projectionArtifactsJson == null && artifactsJson != null) {
      copy(projectionArtifactsJson = artifactsJson)
    } else {
      this
    }

  fun withDecompositionFields(subtaskId: Int, specPath: String): ContinuationStepResult {
    val decorated: WorkflowContinueResult = when (val current = result) {
      is WorkflowContinueResult.Standard -> WorkflowContinueResult.DecompositionStandard(
        dbPath = current.dbPath,
        view = current.view,
        decompositionSubtaskId = subtaskId,
        decompositionSubtaskSpecPath = specPath,
      )
      is WorkflowContinueResult.DecompositionStandard -> current.copy(
        decompositionSubtaskId = subtaskId,
        decompositionSubtaskSpecPath = specPath,
      )
      is WorkflowContinueResult.UnknownWorkflow,
      is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow,
      is WorkflowContinueResult.DecompositionBlockedSubtask,
      is WorkflowContinueResult.DecompositionBlockedBranchStart,
      is WorkflowContinueResult.DecompositionDone,
      is WorkflowContinueResult.DecompositionBlockedGit,
      is WorkflowContinueResult.Error,
      -> error(
        "withDecompositionFields can only decorate Standard or " +
          "DecompositionStandard continuations; got ${current::class.simpleName}",
      )
    }
    return copy(result = decorated)
  }
}

internal fun missingSubtaskWorkflowResult(
  selection: DecompositionContinuationSelection.Resume,
  unitOfWork: UnitOfWork,
): ContinuationStepResult = ContinuationStepResult(
  WorkflowContinueResult.DecompositionMissingSubtaskWorkflow(
    dbPath = unitOfWork.dbPath.toString(),
    subtaskId = selection.subtask.id,
    blockedReason = "Subtask ${selection.subtask.id} is in progress but has no workflow_id.",
  ),
)

internal fun blockedSubtaskResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  selection: DecompositionContinuationSelection.Blocked,
  dbPath: String,
): WorkflowContinueResult = WorkflowContinueResult.DecompositionBlockedSubtask(
  dbPath = dbPath,
  workflowId = parentRecord.workflowId,
  issueKey = manifest.issueKey,
  subtaskId = selection.subtask.id,
  subtaskSpecPath = selection.subtask.specPath,
  blockedReason = selection.reason,
)

internal fun doneDecompositionResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  dbPath: String,
): WorkflowContinueResult = WorkflowContinueResult.DecompositionDone(
  dbPath = dbPath,
  workflowId = parentRecord.workflowId,
  issueKey = manifest.issueKey,
  decompositionStatus = manifest.status,
)

internal fun blockedGitResult(
  parentWorkflowId: String,
  issueKey: String,
  dbPath: String,
  reason: String,
): WorkflowContinueResult = WorkflowContinueResult.DecompositionBlockedGit(
  dbPath = dbPath,
  workflowId = parentWorkflowId,
  issueKey = issueKey,
  blockedReason = reason.ifBlank { "Subtask advancement failed." },
)

internal fun decompositionRuntimeArtifactsJson(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): String = jsonString(
  mapOf(
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
      manifest,
      validator,
      DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
    ),
  ),
)

private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  kotlinx.serialization.json.JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)
