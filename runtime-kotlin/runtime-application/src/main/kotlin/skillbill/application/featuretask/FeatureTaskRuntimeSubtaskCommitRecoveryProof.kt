package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.commitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Path

internal fun WorkflowGitOperations.recoveredSubtaskParent(
  request: RecoveredSubtaskParentRequest,
): WorkflowGitOperationResult {
  val repoRoot = request.repoRoot
  val headSha = request.headSha
  val branch = request.branch
  val identity = request.identity
  val sequenceNumber = request.sequenceNumber
  val prior = request.prior
  val checkedOut = currentBranch(repoRoot)
  val message = commitMessage(repoRoot, headSha)
  val parent = resolveCommit(repoRoot, "$headSha^")
  val invalid = recoveredSubtaskParentInvalid(
    request,
    checkedOut,
    message,
    parent,
  )
  if (invalid) return recoveredSubtaskParentFailure()
  val preserved = resolveCheckpointRef(
    repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    identity.checkpointRefName(sequenceNumber),
  )
  if (!preserved.ok) return preserved
  val target = preserved.value.orEmpty().trim()
  val parentSha = parent.value.orEmpty().trim()
  val refProvesParent = prior == null && target == parentSha ||
    prior != null && target == prior.commitSha && preservedPriorProvesParent(
      repoRoot,
      target,
      parentSha,
      prior,
      identity,
    )
  return if (refProvesParent) {
    parent
  } else {
    WorkflowGitOperationResult(
      status = "error",
      error = "checkpoint ref does not prove the create or amend predecessor",
    )
  }
}

private fun recoveredSubtaskParentInvalid(
  request: RecoveredSubtaskParentRequest,
  checkedOut: WorkflowGitOperationResult,
  message: WorkflowGitOperationResult,
  parent: WorkflowGitOperationResult,
): Boolean = !checkedOut.ok ||
  checkedOut.value.orEmpty().trim() != request.branch ||
  request.prior?.branch != null && request.prior.branch != request.branch ||
  !message.ok ||
  !request.identity.matches(message.value.orEmpty()) ||
  !parent.ok ||
  parent.value.orEmpty().isBlank()

private fun recoveredSubtaskParentFailure() = WorkflowGitOperationResult(
  status = "error",
  error = "recovered commit lacks matching branch, trailer, or parent",
)

private fun WorkflowGitOperations.preservedPriorProvesParent(
  repoRoot: Path,
  target: String,
  parentSha: String,
  prior: FeatureTaskRuntimeCheckpointIdentity,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): Boolean {
  val priorParent = resolveCommit(repoRoot, "$target^")
  val priorMessage = commitMessage(repoRoot, target)
  return priorParent.ok &&
    priorParent.value.orEmpty().trim() == parentSha &&
    prior.parentSha == parentSha &&
    priorMessage.ok &&
    identity.matches(priorMessage.value.orEmpty())
}
