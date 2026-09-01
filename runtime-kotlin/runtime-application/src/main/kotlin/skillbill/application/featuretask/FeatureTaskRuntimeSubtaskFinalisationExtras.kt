package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.stagePaths

internal fun FeatureTaskRuntimeSubtaskFinalisation.prepareStaging(
  stageable: List<String>,
): FinalisationStagingOutcome {
  if (stageable.isEmpty()) return FinalisationStagingReady(restoreState = "")
  val snapshot = gitOperations.captureIndexState(repoRoot, stageable)
  if (!snapshot.ok) {
    return FinalisationStagingBlocked(
      blocked("the pre-finalisation index could not be captured (${snapshot.error})"),
    )
  }
  val staged = gitOperations.stagePaths(repoRoot, stageable)
  if (!staged.ok) {
    return FinalisationStagingBlocked(
      blocked(restoring(staged.error, stageable, snapshot.value.orEmpty())),
    )
  }
  return FinalisationStagingReady(restoreState = snapshot.value.orEmpty())
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.commitAndPush(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  stageable: List<String>,
  excluded: List<String>,
  restoreState: String,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val branch = request.metadata.branch
  val decision = decide(
    branch = branch,
    identity = request.identity,
    durableCommitSha = request.durableCommitSha,
    sequenceNumber = request.sequenceNumber,
  )
  val rewrites = decision is FeatureTaskRuntimeSubtaskCommitAmend
  val message = FeatureTaskRuntimeCheckpointMessage.finalise(
    request.handoff.outcomeMessage,
    request.metadata,
    request.identity,
  )
  val commit = gitOperations.writeSubtaskCommitPreservingHistory(
    SubtaskCommitPreservationRequest(
      repoRoot = repoRoot,
      decision = decision,
      identity = request.identity,
      message = message,
      allowUnchangedIndex = true,
      record = record,
    ),
  )
  if (!commit.ok) return blocked(restoring(commit.error, stageable, restoreState))
  val commitSha = commit.value.orEmpty().trim().takeIf(String::isNotBlank)
    ?: return blocked(restoring("the finalisation commit returned an empty sha", stageable, restoreState))
  val recordFailure = recordCommit(commitSha, stageable)
  if (recordFailure != null) return FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
  return finalizeCommittedSubtask(
    FinalizeCommittedSubtaskInput(
      request = request,
      branch = branch,
      stageable = stageable,
      excluded = excluded,
      commitSha = commitSha,
      rewrites = rewrites,
    ),
  )
}

private data class FinalizeCommittedSubtaskInput(
  val request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  val branch: String,
  val stageable: List<String>,
  val excluded: List<String>,
  val commitSha: String,
  val rewrites: Boolean,
)

private fun FeatureTaskRuntimeSubtaskFinalisation.finalizeCommittedSubtask(
  input: FinalizeCommittedSubtaskInput,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val forcedWithLease = input.rewrites && remoteDiverged(input.branch, input.commitSha)
  val pushFailure = push(input.branch, input.request.identity, input.commitSha, forcedWithLease)
  if (pushFailure != null) return blocked(pushFailure)
  if (!input.request.manifestCommitSha.isNullOrBlank()) {
    gitOperations.pruneSubtaskCheckpointRefs(
      repoRoot = repoRoot,
      request = FeatureTaskRuntimeCheckpointRefPruneRequest(
        issueKey = input.request.identity.issueKey,
        subtaskId = input.request.identity.subtaskId,
        manifestCommitSha = input.request.manifestCommitSha,
        featureBranch = input.branch,
      ),
      record = record,
    )
  }
  return FeatureTaskRuntimeSubtaskFinalised(
    commitSha = input.commitSha,
    stagedPaths = input.stageable,
    excludedSpecPaths = input.excluded,
    forcedWithLease = forcedWithLease,
  )
}

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

internal fun FeatureTaskRuntimeSubtaskFinalisation.headMessage(): String? =
  gitOperations.headCommitMessage(repoRoot).takeIf { it.ok }?.value

internal fun FeatureTaskRuntimeSubtaskFinalisation.ownedHeadAlreadyFinalised(
  durableCommitSha: String?,
): Boolean {
  val durable = durableCommitSha?.trim()?.takeIf(String::isNotBlank) ?: return false
  val headSha = gitOperations.headCommitSha(repoRoot).takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    ?: return false
  if (durable != headSha) return false
  return headMessage().orEmpty().contains(FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK)
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.remoteDiverged(branch: String, commitSha: String): Boolean {
  val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank) ?: return false
  val ancestor = gitOperations.isCommitAncestor(repoRoot, remoteTip, commitSha)
  return ancestor.ok && ancestor.value.orEmpty().trim().equals("false", ignoreCase = true)
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.push(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
  withLease: Boolean,
): String? {
  if (!withLease) {
    val pushed = gitOperations.pushBranch(repoRoot, branch)
    return if (pushed.ok) null else "the finalised subtask commit '$commitSha' could not be pushed (${pushed.error})"
  }
  record(forceWithLeaseRecord(identity, branch, commitSha))
  refreshRemote(identity, branch)
  val first = gitOperations.pushBranchWithLease(repoRoot, branch)
  if (first.ok) return null
  record(leaseRetryRecord(identity, branch, first.error))
  refreshRemote(identity, branch)
  val second = gitOperations.pushBranchWithLease(repoRoot, branch)
  if (second.ok) return null
  record(leaseAbortRecord(identity, branch, second.error))
  return "the reopened subtask's amended commit '$commitSha' could not be pushed (${second.error})"
}

private fun FeatureTaskRuntimeSubtaskFinalisation.refreshRemote(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
) {
  val refreshed = gitOperations.refreshRemoteBranch(repoRoot, branch)
  if (refreshed.ok) {
    record(leaseRefreshRecord(identity, branch))
  } else {
    record(leaseRefreshFailedRecord(identity, branch, refreshed.error))
  }
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.restoring(
  error: String,
  paths: List<String>,
  snapshot: String,
): String {
  val restored = gitOperations.restoreIndexState(repoRoot, paths, snapshot)
  return if (restored.ok) {
    "$error; the pre-finalisation index was restored and the working tree is unchanged"
  } else {
    "$error; the pre-finalisation index could NOT be restored (${restored.error}) — inspect " +
      "`git status` before committing anything yourself"
  }
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.blocked(reason: String) =
  FeatureTaskRuntimeSubtaskFinalisationBlocked(
    "needs_human: subtask finalisation could not complete because $reason.",
  )
