package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.executionModel
import skillbill.application.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowUpdateInput
import java.nio.file.Path

internal fun WorkflowEngine.continueExistingWorkflow(
  family: WorkflowFamily,
  initialRecord: WorkflowStateSnapshot,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
): ContinuationStepResult {
  var record = initialRecord
  val workflowId = initialRecord.workflowId
  val sessionSummary = family.sessionSummary(unitOfWork.workflowStates, record.sessionId.orEmpty())
  var decision = continueDecision(family.definition, record, sessionSummary)
  var projectionArtifactsJson: String? = null
  if (decision.shouldReopen) {
    val originalContinueStatus = decision.view.continueStatus
    val originalWorkflowStatus = decision.view.workflowStatusBeforeContinue
    val runtimeInput = family.withDecompositionRuntime(
      record,
      decision.toReopenInput(record.sessionId),
      workflowId,
      validator,
      fileStore,
    )
    val reopened = updateRecord(family.definition, record, runtimeInput.input)
    family.save(unitOfWork.workflowStates, reopened)
    record = family.get(unitOfWork.workflowStates, workflowId) ?: reopened
    if (runtimeInput.updated) projectionArtifactsJson = record.artifactsJson
    decision = continueDecision(
      family.definition,
      record,
      sessionSummary,
      continueStatusOverride = originalContinueStatus,
      workflowStatusBeforeContinueOverride = originalWorkflowStatus,
    )
  }
  return ContinuationStepResult(
    WorkflowContinueResult.Standard(
      dbPath = unitOfWork.dbPath.toString(),
      view = decision.view,
    ),
    projectionArtifactsJson,
  )
}

internal fun WorkflowEngine.alignSubtaskResumeStep(
  record: WorkflowStateSnapshot,
  resumeStepId: String,
  unitOfWork: UnitOfWork,
): WorkflowStateSnapshot {
  val alignment = resumeAlignment(record, resumeStepId)
  if (
    alignment.targetStepId.isBlank() ||
    (record.currentStepId == alignment.targetStepId && alignment.staleBlockedStep == null)
  ) {
    return record
  }
  val updated = updateRecord(
    WorkflowFamily.IMPLEMENT.definition,
    record,
    WorkflowUpdateInput(
      workflowStatus = record.workflowStatus,
      currentStepId = alignment.targetStepId,
      stepUpdates = alignment.staleBlockedStep?.let { step ->
        listOf(mapOf("step_id" to step.stepId, "status" to "completed", "attempt_count" to step.attemptCount))
      },
      artifactsPatch = null,
      sessionId = record.sessionId.orEmpty(),
    ),
  )
  WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
  return WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, record.workflowId) ?: updated
}

private fun WorkflowEngine.resumeAlignment(record: WorkflowStateSnapshot, requestedStepId: String): ResumeAlignment {
  val steps = snapshotView(WorkflowFamily.IMPLEMENT.definition, record).steps
  val requestedStep = steps.firstOrNull { step -> step.stepId == requestedStepId }
  val targetStepId = requestedStepId.takeIf { stepId ->
    stepId.isNotBlank() && steps.firstOrNull { step -> step.stepId == stepId }?.status == "running"
  }
    ?: steps.firstOrNull { step -> step.status == "running" }?.stepId
    ?: requestedStepId
  val staleBlockedStep = requestedStep?.takeIf { step -> step.stepId != targetStepId && step.status == "blocked" }
  return ResumeAlignment(targetStepId = targetStepId, staleBlockedStep = staleBlockedStep)
}

private data class ResumeAlignment(
  val targetStepId: String,
  val staleBlockedStep: WorkflowStepState?,
)

internal fun WorkflowEngine.persistParentDecompositionRuntime(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  val updatedParent = updateRecord(
    WorkflowFamily.IMPLEMENT.definition,
    parentRecord,
    WorkflowUpdateInput(
      workflowStatus = parentRecord.workflowStatus,
      currentStepId = parentRecord.currentStepId,
      stepUpdates = null,
      artifactsPatch = mapOf(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
          manifest,
          validator,
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
        lastResumableStep = "preplan",
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
