package skillbill.engine.featuretask

import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE

internal fun captureNormalizationIndex(
  request: NormalizedSubtaskCommitRequest,
  paths: List<String>,
): WorkflowGitOperationResult = if (paths.isEmpty()) {
  WorkflowGitOperationResult(status = "ok", value = "")
} else {
  request.runLoop.phaseGates.gitOperations.captureIndexState(request.runLoop.request.repoRoot, paths)
}

internal fun restoreNormalizationIndex(
  request: NormalizedSubtaskCommitRequest,
  paths: List<String>,
  snapshot: String,
): Boolean = paths.isEmpty() || request.runLoop.phaseGates.gitOperations.restoreIndexState(
  request.runLoop.request.repoRoot,
  paths,
  snapshot,
).ok

internal fun updateNormalizedIdentityRef(
  request: NormalizedSubtaskCommitRequest,
  refName: String,
  replacementSha: String,
): String? {
  val git = request.runLoop.phaseGates.gitOperations
  val original = git.resolveCheckpointRef(
    request.runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  if (!original.ok) {
    return "replacement checkpoint ref '$refName' could not be inspected (${original.error}); " +
      "operator decision: repair checkpoint ref access before resuming"
  }
  val updated = git.updateCheckpointRef(
    request.runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
    replacementSha,
  )
  val verified = git.resolveCheckpointRef(
    request.runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  return if (updated.ok && verified.ok && verified.value.orEmpty().trim() == replacementSha) {
    null
  } else {
    "replacement checkpoint ref '$refName' could not be verified; operator decision: restore the branch from the " +
      "preserved checkpoint refs"
  }
}

internal fun NormalizedSubtaskCommitRequest.refusal(reason: String, cause: Throwable? = null) =
  SubtaskMigrationRefusalRequest(precedingPhaseId, branch, blockedReason, reason, cause)
