package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import java.nio.file.Path

internal fun continueExistingWorkflow(
  family: WorkflowFamily,
  initialRecord: WorkflowStateSnapshot,
  workflowId: String,
  unitOfWork: UnitOfWork,
  fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
): ContinuationStepResult {
  var record = initialRecord
  val sessionSummary = family.sessionSummary(unitOfWork.workflowStates, record.sessionId.orEmpty())
  var decision = WorkflowEngine.continueDecision(family.definition, record, sessionSummary)
  var projectionArtifactsJson: String? = null
  if (decision.shouldReopen) {
    val originalContinueStatus = decision.view.continueStatus
    val runtimeInput = family.withDecompositionRuntime(
      record,
      decision.toReopenInput(record.sessionId),
      workflowId,
      fileStore,
    )
    val reopened = WorkflowEngine.updateRecord(family.definition, record, runtimeInput.input)
    family.save(unitOfWork.workflowStates, reopened)
    record = family.get(unitOfWork.workflowStates, workflowId) ?: reopened
    if (runtimeInput.updated) projectionArtifactsJson = record.artifactsJson
    val refreshed = WorkflowEngine.continueDecision(family.definition, record, sessionSummary)
    // Preserve the pre-reopen continue_status so the wire shape matches
    // the historical "reopened" status even though the underlying record
    // is now running again.
    decision = refreshed.copy(view = refreshed.view.copy(continueStatus = originalContinueStatus))
  }
  return ContinuationStepResult(
    WorkflowContinueResult.Standard(
      dbPath = unitOfWork.dbPath.toString(),
      view = decision.view,
    ),
    projectionArtifactsJson,
  )
}

internal fun alignSubtaskResumeStep(
  record: WorkflowStateSnapshot,
  resumeStepId: String,
  unitOfWork: UnitOfWork,
): WorkflowStateSnapshot {
  if (resumeStepId.isBlank() || record.currentStepId == resumeStepId) return record
  val updated = WorkflowEngine.updateRecord(
    WorkflowFamily.IMPLEMENT.definition,
    record,
    WorkflowUpdateInput(
      workflowStatus = record.workflowStatus,
      currentStepId = resumeStepId,
      stepUpdates = null,
      artifactsPatch = null,
      sessionId = record.sessionId.orEmpty(),
    ),
  )
  WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
  return WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, record.workflowId) ?: updated
}

internal fun persistParentDecompositionRuntime(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  unitOfWork: UnitOfWork,
) {
  val updatedParent = WorkflowEngine.updateRecord(
    WorkflowFamily.IMPLEMENT.definition,
    parentRecord,
    WorkflowUpdateInput(
      workflowStatus = parentRecord.workflowStatus,
      currentStepId = parentRecord.currentStepId,
      stepUpdates = null,
      artifactsPatch = mapOf(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
          manifest,
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
        ),
      ),
      sessionId = parentRecord.sessionId.orEmpty(),
    ),
  )
  WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updatedParent)
}

internal fun DecompositionManifest.withStartedSubtask(
  subtaskId: Int,
  workflowId: String,
  branch: String,
): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "in_progress",
        workflowId = workflowId,
        branch = branch.takeIf(String::isNotBlank) ?: subtask.branch,
        lastResumableStep = "assess",
      )
    } else {
      subtask
    }
  },
)

internal fun DecompositionManifest.withCommittedSubtask(subtaskId: Int, commitSha: String): DecompositionManifest =
  copy(subtasks = subtasks.map { if (it.id == subtaskId) it.copy(commitSha = commitSha) else it })

internal fun DecompositionManifest.branchForSubtask(subtaskId: Int): String = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> featureBranch.orEmpty()
  DecompositionExecutionModel.STACKED_BRANCHES ->
    stackBranches.firstOrNull { it.subtaskId == subtaskId }?.branch.orEmpty()
}

internal fun DecompositionManifest.baseForSubtask(subtaskId: Int): String? = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> baseBranch
  DecompositionExecutionModel.STACKED_BRANCHES ->
    stackBranches.firstOrNull { it.subtaskId == subtaskId }?.baseBranch ?: baseBranch
}

internal fun repoRoot(): Path = Path.of("").toAbsolutePath()
