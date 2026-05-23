package skillbill.application

import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.DecompositionContinuationSelector
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput

internal class DecompositionWorkflowContinuation(
  private val gitOperations: WorkflowGitOperations,
) {
  fun continueDecomposedParentByIssueKey(issueKey: String, unitOfWork: UnitOfWork): ContinuationResult {
    val parentRecord = unitOfWork.workflowStates
      .findDecomposedParentWorkflow(issueKey)
      ?.toSnapshot()
    val manifest = parentRecord?.decompositionRuntime()
    val result = if (parentRecord == null || manifest == null) {
      ContinuationResult(unknownWorkflowPayload(issueKey, unitOfWork.dbPath.toString()))
    } else {
      continueManifest(parentRecord, manifest, unitOfWork)
    }
    return result
  }

  private fun continueManifest(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): ContinuationResult {
    val advancement = advanceCompletedSubtasks(parentRecord, manifest, unitOfWork)
    if (advancement.error != null) {
      return ContinuationResult(
        blockedGitPayload(parentRecord.workflowId, manifest.issueKey, unitOfWork.dbPath.toString(), advancement.error),
        advancement.projectionArtifactsJson,
      )
    }
    val advancedManifest = advancement.manifest
    val projectionArtifactsJson =
      if (advancedManifest != manifest) decompositionRuntimeArtifactsJson(advancedManifest) else null
    return selectedContinuation(parentRecord, advancedManifest, unitOfWork)
      .withProjectionArtifactsIfMissing(projectionArtifactsJson)
  }

  private fun selectedContinuation(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): ContinuationResult = when (val selection = DecompositionContinuationSelector.select(manifest)) {
    is DecompositionContinuationSelection.Resume -> continueSelectedSubtask(selection, unitOfWork)
    is DecompositionContinuationSelection.Start -> startSelectedSubtask(parentRecord, manifest, selection, unitOfWork)
    is DecompositionContinuationSelection.Blocked ->
      ContinuationResult(blockedSubtaskPayload(parentRecord, manifest, selection, unitOfWork.dbPath.toString()))
    is DecompositionContinuationSelection.Done ->
      ContinuationResult(doneDecompositionPayload(parentRecord, selection.manifest, unitOfWork.dbPath.toString()))
  }

  private fun continueSelectedSubtask(
    selection: DecompositionContinuationSelection.Resume,
    unitOfWork: UnitOfWork,
  ): ContinuationResult {
    val record = selection.workflowId
      .takeIf(String::isNotBlank)
      ?.let { WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, it) }
    return if (record == null) {
      missingSubtaskWorkflowPayload(selection, unitOfWork)
    } else {
      val alignedRecord = alignSubtaskResumeStep(record, selection.resumeStepId, unitOfWork)
      continueExistingWorkflow(WorkflowFamily.IMPLEMENT, alignedRecord, selection.workflowId, unitOfWork)
        .withDecompositionFields(selection.subtask.id, selection.subtask.specPath)
    }
  }

  private fun startSelectedSubtask(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): ContinuationResult {
    val branchError = checkoutAndValidateBranch(parentRecord, manifest, selection, unitOfWork)
    return if (branchError != null) {
      ContinuationResult(branchError)
    } else {
      openSubtaskWorkflow(parentRecord, manifest, selection, unitOfWork)
    }
  }

  private fun checkoutAndValidateBranch(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): Map<String, Any?>? {
    val branchPlan = selection.branchPlan
    var errorPayload: Map<String, Any?>? = null
    if (branchPlan.branch.isNotBlank()) {
      val checkout = gitOperations.checkoutBranch(repoRoot(), branchPlan.branch, branchPlan.baseBranch)
      errorPayload = checkout.takeUnless { it.ok }
        ?.let { blockedBranchStartPayload(parentRecord, manifest, selection.subtask.id, it.error, unitOfWork) }
      if (errorPayload == null && branchPlan.validateBase) {
        errorPayload = gitOperations.validateBranchBase(repoRoot(), branchPlan.branch, branchPlan.baseBranch)
          .takeUnless { it.ok }
          ?.let { blockedBranchStartPayload(parentRecord, manifest, selection.subtask.id, it.error, unitOfWork) }
      }
    }
    return errorPayload
  }

  private fun openSubtaskWorkflow(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): ContinuationResult {
    val workflowId = generateWorkflowId(WorkflowFamily.IMPLEMENT.definition.workflowIdPrefix)
    val updatedManifest = manifest.withStartedSubtask(selection.subtask.id, workflowId, selection.branchPlan.branch)
    val opened = WorkflowEngine.openRecord(
      WorkflowFamily.IMPLEMENT.definition,
      workflowId,
      parentRecord.sessionId.orEmpty(),
      WorkflowFamily.IMPLEMENT.definition.defaultInitialStepId,
    )
    val started = WorkflowEngine.updateRecord(
      WorkflowFamily.IMPLEMENT.definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "assess",
        stepUpdates = null,
        artifactsPatch = subtaskStartArtifacts(selection, updatedManifest),
        sessionId = parentRecord.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, started)
    persistParentDecompositionRuntime(parentRecord, updatedManifest, unitOfWork)
    val saved = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: started
    return continueExistingWorkflow(WorkflowFamily.IMPLEMENT, saved, workflowId, unitOfWork)
      .withProjection(updatedManifest)
      .withDecompositionFields(selection.subtask.id, selection.subtask.specPath)
  }

  private fun subtaskStartArtifacts(
    selection: DecompositionContinuationSelection.Start,
    manifest: DecompositionManifest,
  ): Map<String, Any?> = mapOf(
    "assessment" to mapOf("spec_path" to selection.subtask.specPath),
    "branch" to mapOf("branch_name" to selection.branchPlan.branch),
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
      manifest,
      DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
    ),
  )

  private fun advanceCompletedSubtasks(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): AdvancementResult {
    var updated = manifest
    manifest.subtasks
      .filter { it.status == "complete" && it.commitSha.isNullOrBlank() }
      .forEach { subtask ->
        val advanced = commitCompletedSubtask(updated, subtask.id, subtask.name)
        if (advanced.error != null) {
          updated = updated.withBlockedSubtask(subtask.id, advanced.error, "commit_push")
          persistParentDecompositionRuntime(parentRecord, updated, unitOfWork)
          return AdvancementResult(updated, advanced.error, decompositionRuntimeArtifactsJson(updated))
        }
        updated = advanced.manifest
      }
    if (updated != manifest) {
      persistParentDecompositionRuntime(parentRecord, updated, unitOfWork)
    }
    return AdvancementResult(updated)
  }

  private fun commitCompletedSubtask(
    manifest: DecompositionManifest,
    subtaskId: Int,
    subtaskName: String,
  ): CommitAdvanceResult {
    val branch = manifest.branchForSubtask(subtaskId)
    val checkout = if (branch.isNotBlank()) {
      gitOperations.checkoutBranch(repoRoot(), branch, manifest.baseForSubtask(subtaskId))
    } else {
      null
    }
    return if (checkout?.ok == false) {
      CommitAdvanceResult(manifest, checkout.error.ifBlank { "Git branch checkout failed." })
    } else {
      val commit = gitOperations.createCommit(repoRoot(), "${manifest.issueKey} subtask $subtaskId: $subtaskName")
      if (commit.ok) {
        CommitAdvanceResult(manifest.withCommittedSubtask(subtaskId, commit.value))
      } else {
        CommitAdvanceResult(manifest, commit.error.ifBlank { "Git commit failed." })
      }
    }
  }
}

private data class AdvancementResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
  val projectionArtifactsJson: String? = null,
)

private data class CommitAdvanceResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
)
