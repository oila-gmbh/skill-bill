package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

internal fun FeatureTaskRuntimeSubtaskFinalisation.decide(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  durableCommitSha: String?,
  sequenceNumber: Int,
): FeatureTaskRuntimeSubtaskCommitDecision {
  val headSha = gitOperations.headCommitSha(repoRoot).takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, branch)
  return FeatureTaskRuntimeSubtaskCommitResolver.decide(
    identity = identity,
    durableCommitSha = durableCommitSha,
    head = FeatureTaskRuntimeSubtaskCommitHeadState(
      sha = headSha,
      commitMessage = if (durableCommitSha == null && headSha != null) headMessage() else null,
      isUnpushed = unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true),
    ),
    sequenceNumber = sequenceNumber,
  )
}

fun FeatureTaskRuntimeSubtaskFinalisation.headMessage(): String? =
  gitOperations.headCommitMessage(repoRoot).takeIf { it.ok }?.value

fun FeatureTaskRuntimeSubtaskFinalisation.ownedHeadAlreadyFinalised(durableCommitSha: String?): Boolean {
  val durable = durableCommitSha?.trim()?.takeIf(String::isNotBlank) ?: return false
  val headSha = gitOperations.headCommitSha(repoRoot).takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    ?: return false
  if (durable != headSha) return false
  return headMessage().orEmpty().contains(FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK)
}

fun FeatureTaskRuntimeSubtaskFinalisation.remoteDiverged(branch: String, commitSha: String): Boolean {
  val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank) ?: return false
  val ancestor = gitOperations.isCommitAncestor(repoRoot, remoteTip, commitSha)
  return ancestor.ok && ancestor.value.orEmpty().trim().equals("false", ignoreCase = true)
}

fun FeatureTaskRuntimeSubtaskFinalisation.push(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
  withLease: Boolean,
): String? {
  if (!withLease) {
    val pushed = gitOperations.pushBranch(repoRoot, branch)
    return if (pushed.ok) null else "the finalised subtask commit '$commitSha' could not be pushed (${pushed.error})"
  }
  return pushWithLease(branch, identity, commitSha)
}

private fun FeatureTaskRuntimeSubtaskFinalisation.pushWithLease(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
): String? {
  record(forceWithLeaseRecord(identity, branch, commitSha))
  var lastError: String? = null
  for (attempt in 0 until 2) {
    val refreshed = refreshRemote(identity, branch)
    if (refreshed.namesAbsentRemote()) {
      return pushAmended(branch, commitSha)
    }
    val result = gitOperations.pushBranchWithLease(repoRoot, branch)
    if (result.ok) {
      return null
    }
    lastError = result.error
    if (attempt == 0) {
      record(leaseRetryRecord(identity, branch, result.error))
    }
  }
  record(leaseAbortRecord(identity, branch, lastError ?: "unknown push failure"))
  return "the reopened subtask's amended commit '$commitSha' could not be pushed ($lastError)"
}

private fun FeatureTaskRuntimeSubtaskFinalisation.pushAmended(branch: String, commitSha: String): String? {
  val pushed = gitOperations.pushBranch(repoRoot, branch)
  return if (pushed.ok) {
    null
  } else {
    "the reopened subtask's amended commit '$commitSha' could not be pushed (${pushed.error})"
  }
}

private fun WorkflowGitOperationResult.namesAbsentRemote(): Boolean =
  ok && value.trim() == WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH

private fun FeatureTaskRuntimeSubtaskFinalisation.refreshRemote(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
): WorkflowGitOperationResult {
  val refreshed = gitOperations.refreshRemoteBranch(repoRoot, branch)
  when {
    refreshed.namesAbsentRemote() -> record(leaseAbsentRecord(identity, branch))
    refreshed.ok -> record(leaseRefreshRecord(identity, branch))
    else -> record(leaseRefreshFailedRecord(identity, branch, refreshed.error))
  }
  return refreshed
}
