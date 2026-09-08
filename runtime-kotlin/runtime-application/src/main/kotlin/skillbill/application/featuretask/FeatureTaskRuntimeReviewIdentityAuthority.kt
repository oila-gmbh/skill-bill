package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.commitMessage
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE

internal fun WorkflowGitOperations.reviewIdentityStillAuthoritative(request: ReviewIdentityAuthorityRequest): Boolean =
  request.hasMatchingRevision() && reviewIdentityReadEvidence(request)

private fun ReviewIdentityAuthorityRequest.hasMatchingRevision(): Boolean = reviewedTreeSha == currentTreeSha &&
  checkpoint?.commitSha == currentHeadSha &&
  checkpoint.issueKey == identity.issueKey &&
  checkpoint.subtaskId == identity.subtaskId

private fun WorkflowGitOperations.reviewIdentityReadEvidence(request: ReviewIdentityAuthorityRequest): Boolean {
  val checkpoint = request.checkpoint ?: return false
  val branch = currentBranch(request.repoRoot)
  val reviewedMessage = commitMessage(request.repoRoot, request.reviewedTargetSha)
  val preserved = resolveCheckpointRef(
    request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    checkpoint.checkpointRef,
  )
  val headMessage = headCommitMessage(request.repoRoot)
  val reviewedParent = resolveCommit(request.repoRoot, "${request.reviewedTargetSha}^")
  val currentParent = resolveCommit(request.repoRoot, "${request.currentHeadSha}^")
  val readFailure = reviewIdentityReadFailure(
    request,
    ReviewIdentityReadResults(
      branchOk = branch.ok,
      branchError = branch.error,
      reviewedMessageOk = reviewedMessage.ok,
      reviewedMessageError = reviewedMessage.error,
      preservedOk = preserved.ok,
      preservedError = preserved.error,
      headMessageOk = headMessage.ok,
      headMessageError = headMessage.error,
      reviewedParentOk = reviewedParent.ok,
      reviewedParentError = reviewedParent.error,
      currentParentOk = currentParent.ok,
      currentParentError = currentParent.error,
    ),
  )
  readFailure?.let { request.onReadFailure?.invoke(it) }
  val preservedSha = preserved.value.orEmpty().trim()
  val reviewedParentSha = reviewedParent.value.orEmpty().trim()
  val currentParentSha = currentParent.value.orEmpty().trim()
  val expectedPreservedSha = if (request.reviewedTargetSha == request.currentHeadSha) {
    currentParentSha
  } else {
    request.reviewedTargetSha
  }
  return readFailure == null &&
    branch.value.trim() == checkpoint.branch &&
    request.identity.matches(reviewedMessage.value.orEmpty()) &&
    preservedSha.isNotBlank() &&
    request.identity.matches(headMessage.value.orEmpty()) &&
    checkpoint.parentSha.orEmpty().trim() == currentParentSha &&
    reviewedParentSha == currentParentSha &&
    preservedSha == expectedPreservedSha
}

private fun reviewIdentityReadFailure(
  request: ReviewIdentityAuthorityRequest,
  results: ReviewIdentityReadResults,
): String? = when {
  !results.branchOk -> "checked-out branch could not be read (${results.branchError})"
  !results.reviewedMessageOk ->
    "reviewed target '${request.reviewedTargetSha}' could not be read (${results.reviewedMessageError})"
  !results.preservedOk ->
    "checkpoint ref '${request.checkpoint?.checkpointRef}' could not be read (${results.preservedError})"
  !results.headMessageOk -> "current HEAD message could not be read (${results.headMessageError})"
  !results.reviewedParentOk -> "reviewed target parent could not be read (${results.reviewedParentError})"
  !results.currentParentOk -> "current HEAD parent could not be read (${results.currentParentError})"
  else -> null
}

private data class ReviewIdentityReadResults(
  val branchOk: Boolean,
  val branchError: String?,
  val reviewedMessageOk: Boolean,
  val reviewedMessageError: String?,
  val preservedOk: Boolean,
  val preservedError: String?,
  val headMessageOk: Boolean,
  val headMessageError: String?,
  val reviewedParentOk: Boolean,
  val reviewedParentError: String?,
  val currentParentOk: Boolean,
  val currentParentError: String?,
)
