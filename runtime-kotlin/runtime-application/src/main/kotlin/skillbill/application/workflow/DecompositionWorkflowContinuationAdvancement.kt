package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.withBlockedSubtask
import skillbill.application.workflow.model.AdvanceCompletedSubtasksRequest
import skillbill.application.workflow.model.CheckoutAndValidateBranchRequest
import skillbill.application.workflow.model.GoalContinuationOutcome
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path

internal data class AdvancementResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
  val projectionArtifactsJson: String? = null,
)

internal data class CommitAdvanceResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
)

internal fun WorkflowEngine.advanceCompletedSubtasks(request: AdvanceCompletedSubtasksRequest): AdvancementResult {
  var updated = request.manifest
  request.manifest.subtasks
    .filter { it.status == "complete" && it.commitSha.isNullOrBlank() }
    .forEach { subtask ->
      val advanced = commitCompletedSubtask(
        updated,
        subtask.id,
        subtask.name,
        request.gitOperations,
        request.repoRootProvider,
      )
      if (advanced.error != null) {
        updated = updated.withBlockedSubtask(subtask.id, advanced.error, "commit_push")
        persistParentDecompositionRuntime(request.parentRecord, updated, request.unitOfWork, request.validator)
        return AdvancementResult(updated, advanced.error, decompositionRuntimeArtifactsJson(updated, request.validator))
      }
      updated = advanced.manifest
    }
  if (updated != request.manifest) {
    persistParentDecompositionRuntime(request.parentRecord, updated, request.unitOfWork, request.validator)
  }
  return AdvancementResult(updated)
}

internal fun commitCompletedSubtask(
  manifest: DecompositionManifest,
  subtaskId: Int,
  subtaskName: String,
  gitOperations: WorkflowGitOperations,
  repoRootProvider: () -> Path,
): CommitAdvanceResult {
  val branch = manifest.branchForSubtask(subtaskId)
  val checkout = if (branch.isNotBlank()) {
    gitOperations.checkoutBranch(repoRootProvider(), branch, manifest.baseForSubtask(subtaskId))
  } else {
    null
  }
  return if (checkout?.ok == false) {
    CommitAdvanceResult(manifest, checkout.error.ifBlank { "Git branch checkout failed." })
  } else {
    val commitMessage = "${manifest.issueKey} subtask $subtaskId: $subtaskName"
    val commit = gitOperations.createCommit(repoRootProvider(), commitMessage)
    if (commit.ok) {
      CommitAdvanceResult(manifest.withCommittedSubtask(subtaskId, commit.value))
    } else {
      CommitAdvanceResult(manifest, commit.error.ifBlank { "Git commit failed." })
    }
  }
}

fun WorkflowEngine.checkoutAndValidateBranch(request: CheckoutAndValidateBranchRequest): WorkflowContinueResult? {
  val branchPlan = request.selection.branchPlan
  fun blockedBranchStartResult(reason: String): WorkflowContinueResult {
    val blockedManifest = request.manifest.withBlockedSubtask(request.selection.subtask.id, reason, "create_branch")
    persistParentDecompositionRuntime(request.parentRecord, blockedManifest, request.unitOfWork, request.validator)
    return WorkflowContinueResult.DecompositionBlockedBranchStart(
      dbPath = request.unitOfWork.dbPath.toString(),
      workflowId = request.parentRecord.workflowId,
      issueKey = request.manifest.issueKey,
      blockedReason = reason.ifBlank { "Git operation failed." },
    )
  }
  var errorResult: WorkflowContinueResult? = null
  if (branchPlan.branch.isNotBlank()) {
    val checkout = request.gitOperations.checkoutBranch(
      request.repoRootProvider(),
      branchPlan.branch,
      branchPlan.baseBranch,
    )
    errorResult = checkout.takeUnless { it.ok }?.let { blockedBranchStartResult(it.error) }
    if (errorResult == null && branchPlan.validateBase) {
      errorResult = request.gitOperations.validateBranchBase(
        request.repoRootProvider(),
        branchPlan.branch,
        branchPlan.baseBranch,
      )
        .takeUnless { it.ok }
        ?.let { blockedBranchStartResult(it.error) }
    }
  }
  return errorResult
}

fun subtaskStartArtifacts(
  selection: DecompositionContinuationSelection.Start,
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
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

fun parentProjectionArtifacts(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  existingArtifactsJson: String,
): Map<String, Any?> = LinkedHashMap(decodeArtifacts(existingArtifactsJson)).apply {
  remove("goal_review_policy")
  remove("goal_out_of_band_acceptances")
  put(
    DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
    encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
  )
}

fun terminalSubtaskResult(
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

fun DecompositionSubtask.toGoalContinuationOutcome(issueKey: String): GoalContinuationOutcome = GoalContinuationOutcome(
  issueKey = issueKey,
  subtaskId = id,
  status = status,
  workflowId = workflowId.orEmpty(),
  commitSha = commitSha,
  blockedReason = blockedReason,
  lastResumableStep = lastResumableStep,
)
