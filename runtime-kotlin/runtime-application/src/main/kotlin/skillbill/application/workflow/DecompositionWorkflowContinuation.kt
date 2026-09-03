package skillbill.application.workflow

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.application.workflow.model.AdvanceCompletedSubtasksRequest
import skillbill.application.workflow.model.CheckoutAndValidateBranchRequest
import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionContinuationSelector
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.nio.file.Path

class DecompositionWorkflowContinuation(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val validator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  private val repoRoot: Path,
  private val manifestWriter: DecompositionManifestWriter,
) {
  internal fun continueDecomposedParentByIssueKey(
    issueKey: String,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int? = null,
  ): ContinuationStepResult {
    val diskManifest = findProjectedManifestByIssueKey(issueKey)
    var parentRecord = unitOfWork.workflowStates
      .findDecomposedParentWorkflow(issueKey, validator, diskManifest)
      ?.toSnapshot()
    var manifest = parentRecord?.decompositionRuntime(validator)
    if (parentRecord == null || manifest == null) {
      if (diskManifest != null) {
        parentRecord = bootstrapParentWorkflowFromManifest(diskManifest, unitOfWork)
        manifest = parentRecord.decompositionRuntime(validator)
      }
    }
    val result = if (parentRecord == null || manifest == null) {
      ContinuationStepResult(
        WorkflowContinueResult.UnknownWorkflow(
          dbPath = unitOfWork.dbPath.toString(),
          workflowId = issueKey,
        ),
      )
    } else {
      unitOfWork.workflowStates.getFeatureTaskWorkflow(parentRecord.workflowId)
        ?.requireRuntimeModeForEngineWrite()
      continueManifest(parentRecord, manifest, unitOfWork, requestedSubtaskId)
    }
    return result
  }

  private fun findProjectedManifestByIssueKey(issueKey: String): DecompositionManifest? {
    if (fileStore === UnavailableDecompositionManifestFileStore) return null
    return resolveDecompositionManifest(
      repoRoot = repoRoot,
      issueKey = issueKey,
      fileStore = fileStore,
      validator = validator,
    )
  }

  private fun bootstrapParentWorkflowFromManifest(
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): WorkflowStateSnapshot {
    val issueKey = normalizeRequiredIssueKey(manifest.issueKey)
    val existingRecord = unitOfWork.workflowStates.findDecomposedParentOrCorruptFallback(
      manifest.issueKey,
      validator,
      manifest,
    )
    existingRecord?.requireRuntimeModeForEngineWrite()
    val existing = existingRecord?.toSnapshot()
    val base = existing ?: engine.openRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix),
      WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix,
      "plan",
    )
    existing?.let { migrateLegacyGoalRunnerControls(unitOfWork, it) }
    val imported = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      base,
      WorkflowUpdateInput(
        workflowStatus = "paused",
        currentStepId = "plan",
        stepUpdates = if (existing != null) {
          null
        } else {
          listOf(
            mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
            mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          )
        },
        artifactsPatch = parentProjectionArtifacts(manifest, validator, base.artifactsJson),
        sessionId = base.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      imported.toRecord().copy(issueKey = issueKey),
    )
    return WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, imported.workflowId) ?: imported
  }

  private fun continueManifest(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int?,
  ): ContinuationStepResult {
    val advancement = if (requestedSubtaskId == null) {
      engine.advanceCompletedSubtasks(
        AdvanceCompletedSubtasksRequest(
          parentRecord = parentRecord,
          manifest = manifest,
          unitOfWork = unitOfWork,
          validator = validator,
          gitOperations = gitOperations,
          repoRootProvider = { repoRoot },
        ),
      )
    } else {
      AdvancementResult(manifest)
    }
    if (advancement.error != null) {
      return ContinuationStepResult(
        blockedGitResult(parentRecord.workflowId, manifest.issueKey, unitOfWork.dbPath.toString(), advancement.error),
        advancement.projectionArtifactsJson,
      )
    }
    val advancedManifest = advancement.manifest
    val projectionArtifactsJson =
      if (advancedManifest != manifest) decompositionRuntimeArtifactsJson(advancedManifest, validator) else null
    return selectedContinuation(parentRecord, advancedManifest, unitOfWork, requestedSubtaskId)
      .withProjectionArtifactsIfMissing(projectionArtifactsJson)
  }

  private fun selectedContinuation(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int?,
  ): ContinuationStepResult = when (
    val selection = DecompositionContinuationSelector.select(manifest, requestedSubtaskId)
  ) {
    is DecompositionContinuationSelection.Resume -> continueSelectedSubtask(manifest, selection, unitOfWork)
    is DecompositionContinuationSelection.Start -> startSelectedSubtask(parentRecord, manifest, selection, unitOfWork)
    is DecompositionContinuationSelection.Blocked ->
      ContinuationStepResult(blockedSubtaskResult(parentRecord, manifest, selection, unitOfWork.dbPath.toString()))
    is DecompositionContinuationSelection.TerminalSubtask ->
      ContinuationStepResult(terminalSubtaskResult(parentRecord, manifest, selection, unitOfWork.dbPath.toString()))
    is DecompositionContinuationSelection.Done ->
      ContinuationStepResult(doneDecompositionResult(parentRecord, selection.manifest, unitOfWork.dbPath.toString()))
  }

  private fun continueSelectedSubtask(
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Resume,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val record = selection.workflowId
      .takeIf(String::isNotBlank)
      ?.let { WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, it) }
    return if (record == null) {
      missingSubtaskWorkflowResult(selection, unitOfWork)
    } else {
      val alignedRecord = engine.alignSubtaskResumeStep(record, selection.resumeStepId, unitOfWork)
      engine.continueExistingWorkflow(
        WorkflowFamily.TASK_RUNTIME,
        alignedRecord,
        unitOfWork,
        ContinueExistingWorkflowArgs(
          validator = validator,
          fileStore = fileStore,
          repoRoot = repoRoot,
          manifestWriter = manifestWriter,
        ),
      ).withDecompositionFields(
        issueKey = manifest.issueKey,
        subtaskId = selection.subtask.id,
        specPath = selection.subtask.specPath,
        outcome = selection.subtask.toGoalContinuationOutcome(manifest.issueKey),
      )
    }
  }

  private fun startSelectedSubtask(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val issueKey = normalizeRequiredIssueKey(manifest.issueKey)
    val branchError = engine.checkoutAndValidateBranch(
      CheckoutAndValidateBranchRequest(
        parentRecord = parentRecord,
        manifest = manifest,
        selection = selection,
        unitOfWork = unitOfWork,
        validator = validator,
        gitOperations = gitOperations,
        repoRootProvider = { repoRoot },
      ),
    )
    return if (branchError != null) {
      ContinuationStepResult(branchError)
    } else {
      openSubtaskWorkflow(parentRecord, manifest, selection, issueKey, unitOfWork)
    }
  }

  private fun openSubtaskWorkflow(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    issueKey: String,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val workflowId = generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix)
    val updatedManifest = manifest.withStartedSubtask(selection.subtask.id, workflowId, selection.branchPlan.branch)
    val opened = engine.openRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      workflowId,
      parentRecord.sessionId.orEmpty(),
      "preplan",
    )
    val started = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "preplan",
        stepUpdates = listOf(
          mapOf("step_id" to "preplan", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = subtaskStartArtifacts(selection, updatedManifest, validator),
        sessionId = parentRecord.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      started.toRecord().copy(issueKey = issueKey),
    )
    engine.persistParentDecompositionRuntime(parentRecord, updatedManifest, unitOfWork, validator)
    val saved = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: started
    return engine.continueExistingWorkflow(
      WorkflowFamily.TASK_RUNTIME,
      saved,
      unitOfWork,
      ContinueExistingWorkflowArgs(
        validator = validator,
        fileStore = fileStore,
        repoRoot = repoRoot,
        manifestWriter = manifestWriter,
      ),
    )
      .withProjection(updatedManifest, validator)
      .withDecompositionFields(
        issueKey = manifest.issueKey,
        subtaskId = selection.subtask.id,
        specPath = selection.subtask.specPath,
        outcome = updatedManifest.subtasks.single { it.id == selection.subtask.id }
          .toGoalContinuationOutcome(manifest.issueKey),
      )
  }
}
