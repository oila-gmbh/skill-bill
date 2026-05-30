package skillbill.application

import skillbill.application.model.GoalContinuationOutcome
import skillbill.application.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.DecompositionContinuationSelector
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput

internal class DecompositionWorkflowContinuation(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val validator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
) {
  fun continueDecomposedParentByIssueKey(
    issueKey: String,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int? = null,
  ): ContinuationStepResult {
    val parentRecord = unitOfWork.workflowStates
      .findDecomposedParentWorkflow(issueKey, validator)
      ?.toSnapshot()
    val manifest = parentRecord?.decompositionRuntime(validator)
    val result = if (parentRecord == null || manifest == null) {
      ContinuationStepResult(
        WorkflowContinueResult.UnknownWorkflow(
          dbPath = unitOfWork.dbPath.toString(),
          workflowId = issueKey,
        ),
      )
    } else {
      continueManifest(parentRecord, manifest, unitOfWork, requestedSubtaskId)
    }
    return result
  }

  private fun continueManifest(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int?,
  ): ContinuationStepResult {
    val advancement = advanceCompletedSubtasks(parentRecord, manifest, unitOfWork)
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
      ?.let { WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, it) }
    return if (record == null) {
      missingSubtaskWorkflowResult(selection, unitOfWork)
    } else {
      val alignedRecord = engine.alignSubtaskResumeStep(record, selection.resumeStepId, unitOfWork)
      engine.continueExistingWorkflow(
        WorkflowFamily.IMPLEMENT,
        alignedRecord,
        unitOfWork,
        validator,
        fileStore,
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
    val branchError = checkoutAndValidateBranch(parentRecord, manifest, selection, unitOfWork)
    return if (branchError != null) {
      ContinuationStepResult(branchError)
    } else {
      openSubtaskWorkflow(parentRecord, manifest, selection, unitOfWork)
    }
  }

  private fun checkoutAndValidateBranch(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): WorkflowContinueResult? {
    val branchPlan = selection.branchPlan
    fun blockedBranchStartResult(reason: String): WorkflowContinueResult {
      val blockedManifest = manifest.withBlockedSubtask(selection.subtask.id, reason, "create_branch")
      engine.persistParentDecompositionRuntime(parentRecord, blockedManifest, unitOfWork, validator)
      return WorkflowContinueResult.DecompositionBlockedBranchStart(
        dbPath = unitOfWork.dbPath.toString(),
        workflowId = parentRecord.workflowId,
        issueKey = manifest.issueKey,
        blockedReason = reason.ifBlank { "Git operation failed." },
      )
    }
    var errorResult: WorkflowContinueResult? = null
    if (branchPlan.branch.isNotBlank()) {
      val checkout = gitOperations.checkoutBranch(repoRoot(), branchPlan.branch, branchPlan.baseBranch)
      errorResult = checkout.takeUnless { it.ok }?.let { blockedBranchStartResult(it.error) }
      if (errorResult == null && branchPlan.validateBase) {
        errorResult = gitOperations.validateBranchBase(repoRoot(), branchPlan.branch, branchPlan.baseBranch)
          .takeUnless { it.ok }
          ?.let { blockedBranchStartResult(it.error) }
      }
    }
    return errorResult
  }

  private fun openSubtaskWorkflow(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val workflowId = generateWorkflowId(WorkflowFamily.IMPLEMENT.definition.workflowIdPrefix)
    val updatedManifest = manifest.withStartedSubtask(selection.subtask.id, workflowId, selection.branchPlan.branch)
    val opened = engine.openRecord(
      WorkflowFamily.IMPLEMENT.definition,
      workflowId,
      parentRecord.sessionId.orEmpty(),
      "preplan",
    )
    val started = engine.updateRecord(
      WorkflowFamily.IMPLEMENT.definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "preplan",
        stepUpdates = listOf(
          mapOf("step_id" to "assess", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "create_branch", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "preplan", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = subtaskStartArtifacts(selection, updatedManifest),
        sessionId = parentRecord.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, started)
    engine.persistParentDecompositionRuntime(parentRecord, updatedManifest, unitOfWork, validator)
    val saved = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: started
    return engine.continueExistingWorkflow(
      WorkflowFamily.IMPLEMENT,
      saved,
      unitOfWork,
      validator,
      fileStore,
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

  private fun subtaskStartArtifacts(
    selection: DecompositionContinuationSelection.Start,
    manifest: DecompositionManifest,
  ): Map<String, Any?> = mapOf(
    "assessment" to mapOf(
      "spec_path" to selection.subtask.specPath,
      "goal_continuation" to true,
      "issue_key" to manifest.issueKey,
      "subtask_id" to selection.subtask.id,
      "accepted_without_user_confirmation" to true,
    ),
    "branch" to mapOf(
      "branch_name" to selection.branchPlan.branch,
      "branch" to selection.branchPlan.branch,
      "goal_continuation" to true,
    ),
    "goal_continuation" to mapOf(
      "enabled" to true,
      "issue_key" to manifest.issueKey,
      "subtask_id" to selection.subtask.id,
      "suppress_pr" to true,
      "outcome_authority" to "workflow_store",
    ),
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
      manifest,
      validator,
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
          engine.persistParentDecompositionRuntime(parentRecord, updated, unitOfWork, validator)
          return AdvancementResult(updated, advanced.error, decompositionRuntimeArtifactsJson(updated, validator))
        }
        updated = advanced.manifest
      }
    if (updated != manifest) {
      engine.persistParentDecompositionRuntime(parentRecord, updated, unitOfWork, validator)
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

private fun terminalSubtaskResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  selection: DecompositionContinuationSelection.TerminalSubtask,
  dbPath: String,
): WorkflowContinueResult = WorkflowContinueResult.DecompositionSubtaskOutcome(
  dbPath = dbPath,
  workflowId = parentRecord.workflowId,
  issueKey = manifest.issueKey,
  subtaskId = selection.subtask.id,
  subtaskSpecPath = selection.subtask.specPath,
  outcome = selection.subtask.toGoalContinuationOutcome(manifest.issueKey),
)

private fun skillbill.workflow.model.DecompositionSubtask.toGoalContinuationOutcome(
  issueKey: String,
): GoalContinuationOutcome = GoalContinuationOutcome(
  issueKey = issueKey,
  subtaskId = id,
  status = status,
  workflowId = workflowId.orEmpty(),
  commitSha = commitSha,
  blockedReason = blockedReason,
  lastResumableStep = lastResumableStep,
)

private data class AdvancementResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
  val projectionArtifactsJson: String? = null,
)

private data class CommitAdvanceResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
)
