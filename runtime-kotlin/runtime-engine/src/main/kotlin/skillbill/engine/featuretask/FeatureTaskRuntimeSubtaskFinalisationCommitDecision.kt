package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

internal fun FeatureTaskRuntimeSubtaskFinalisation.decide(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  durableCommitSha: String?,
  sequenceNumber: Int,
): FeatureTaskRuntimeSubtaskCommitDecision {
  val headSha = gitOperations.headCommitSha(repoRoot)
    .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)  val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, branch)
  return FeatureTaskRuntimeSubtaskCommitResolver.decide(
    identity = identity,
    durableCommitSha = durableCommitSha,
    head = FeatureTaskRuntimeSubtaskCommitHeadState(
      sha = headSha,
      commitMessage = if (durableCommitSha == null && headSha != null) headMessage() else null,
      isUnpushed = unpushed is WorkflowGitOperationResult.Ok &&
        unpushed.value.orEmpty().trim().equals("true", ignoreCase = true),    ),
    sequenceNumber = sequenceNumber,
  )
}

fun FeatureTaskRuntimeSubtaskFinalisation.headMessage(): String? =
  gitOperations.headCommitMessage(repoRoot).takeIf { it is WorkflowGitOperationResult.Ok }?.value

fun FeatureTaskRuntimeSubtaskFinalisation.ownedHeadAlreadyFinalised(durableCommitSha: String?): Boolean {
  val durable = durableCommitSha?.trim()?.takeIf(String::isNotBlank) ?: return false
  val headSha = gitOperations.headCommitSha(repoRoot)
    .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)
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
