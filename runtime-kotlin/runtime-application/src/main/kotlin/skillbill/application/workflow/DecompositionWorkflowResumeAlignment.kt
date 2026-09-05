package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.executionModel
import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal fun WorkflowEngine.continueExistingWorkflow(
  family: WorkflowFamily,
  initialRecord: WorkflowStateSnapshot,
  unitOfWork: UnitOfWork,
  args: ContinueExistingWorkflowArgs,
): ContinuationStepResult {
  var record = initialRecord
  val workflowId = initialRecord.workflowId
  val sessionSummary = family.sessionSummary(unitOfWork.workflowStates, record.sessionId.orEmpty())
  var decision = continueDecision(family.definition, record, sessionSummary)
  var projectionArtifactsJson: String? = null
  if (decision.shouldReopen) {
    val originalContinueStatus = decision.view.continueStatus
    val originalWorkflowStatus = decision.view.workflowStatusBeforeContinue
    val reopenInput = decision.toReopenInput(record.sessionId)
    val effectiveInput =
      if (canRefreshDecompositionRuntime(family, args)) {
        family.withDecompositionRuntime(
          DecompositionRuntimeWriteArgs(
            existing = record,
            input = reopenInput,
            workflowId = workflowId,
            validator = requireNotNull(args.validator),
            fileStore = args.fileStore,
            repoRoot = requireNotNull(args.repoRoot),
            manifestWriter = requireNotNull(args.manifestWriter),
          ),
        ).input
      } else {
        reopenInput
      }
    val reopened = updateRecord(family.definition, record, effectiveInput)
    family.save(unitOfWork.workflowStates, reopened)
    record = family.get(unitOfWork.workflowStates, workflowId) ?: reopened
    val validator = args.validator
    if (
      family == WorkflowFamily.TASK_RUNTIME &&
      validator != null &&
      record.decompositionRuntime(validator) != null
    ) {
      projectionArtifactsJson = record.artifactsJson
    }
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
    projectionArtifactsJson = projectionArtifactsJson,
  )
}

private fun canRefreshDecompositionRuntime(family: WorkflowFamily, args: ContinueExistingWorkflowArgs): Boolean =
  family == WorkflowFamily.TASK_RUNTIME && args.hasWriteTargets()

private fun ContinueExistingWorkflowArgs.hasWriteTargets(): Boolean =
  validator != null && repoRoot != null && manifestWriter != null

fun WorkflowEngine.alignSubtaskResumeStep(
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
    WorkflowFamily.TASK_RUNTIME.definition,
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
  WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
  return WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, record.workflowId) ?: updated
}

private fun WorkflowEngine.resumeAlignment(record: WorkflowStateSnapshot, requestedStepId: String): ResumeAlignment {
  val steps = snapshotView(WorkflowFamily.TASK_RUNTIME.definition, record).steps
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

fun WorkflowEngine.persistParentDecompositionRuntime(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  migrateLegacyGoalRunnerControls(unitOfWork, parentRecord)
  val updatedParent = updateRecord(
    WorkflowFamily.TASK_RUNTIME.definition,
    parentRecord,
    WorkflowUpdateInput(
      workflowStatus = parentRecord.workflowStatus,
      currentStepId = parentRecord.currentStepId,
      stepUpdates = null,
      artifactsPatch = LinkedHashMap(decodeArtifacts(parentRecord.artifactsJson)).apply {
        remove("goal_review_policy")
        remove("goal_out_of_band_acceptances")
        put(
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
          encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
        )
      },
      sessionId = parentRecord.sessionId.orEmpty(),
      replaceArtifacts = true,
    ),
  )
  WorkflowFamily.TASK_RUNTIME.saveRecord(
    unitOfWork.workflowStates,
    updatedParent.toRecord().copy(issueKey = manifest.issueKey),
  )
}

fun DecompositionManifest.withStartedSubtask(
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

fun DecompositionManifest.withCommittedSubtask(subtaskId: Int, commitSha: String): DecompositionManifest =
  copy(subtasks = subtasks.map { if (it.id == subtaskId) it.copy(commitSha = commitSha) else it })

fun DecompositionManifest.branchForSubtask(subtaskId: Int): String = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> featureBranch.orEmpty()
  DecompositionExecutionModel.STACKED_BRANCHES ->
    stackBranches.firstOrNull { it.subtaskId == subtaskId }?.branch.orEmpty()
}

fun DecompositionManifest.baseForSubtask(subtaskId: Int): String? = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> baseBranch
  DecompositionExecutionModel.STACKED_BRANCHES ->
    stackBranches.firstOrNull { it.subtaskId == subtaskId }?.baseBranch ?: baseBranch
}
