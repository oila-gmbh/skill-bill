package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalised
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.restoreIndexState

internal fun FeatureTaskRuntimeSubtaskFinalisation.restoreForeignIndex(
  paths: List<String>,
  snapshot: String,
): String? {
  if (paths.isEmpty()) return null
  val restored = gitOperations.restoreIndexState(repoRoot, paths, snapshot)
  return restored.error.takeIf { !restored.ok }
    ?.let { "the foreign staged index could NOT be restored ($it)" }
}

internal sealed interface FinalisationCommitShaOutcome

internal data class FinalisationCommitShaReady(val value: String) : FinalisationCommitShaOutcome

internal data class FinalisationCommitShaBlocked(val reason: String) : FinalisationCommitShaOutcome

internal fun FeatureTaskRuntimeSubtaskFinalisation.finalisationCommitSha(
  commit: WorkflowGitOperationResult,
  stageable: List<String>,
  restoreState: String,
): FinalisationCommitShaOutcome {
  if (!commit.ok) return FinalisationCommitShaBlocked(restoring(commit.error, stageable, restoreState))
  val sha = commit.value.orEmpty().trim()
  return if (sha.isBlank()) {
    FinalisationCommitShaBlocked(
      restoring("the finalisation commit returned an empty sha", stageable, restoreState),
    )
  } else {
    FinalisationCommitShaReady(sha)
  }
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.restoreForeignFinalisationIndex(
  reason: String,
  foreignStagedPaths: List<String>,
  foreignSnapshot: String,
): String {
  val restored = restoreForeignIndex(foreignStagedPaths, foreignSnapshot)
  return restored?.let { "$reason; $it" } ?: reason
}

internal fun FeatureTaskRuntimeSubtaskFinalisation.commitAndPush(
  input: FinalisationCommitRequest,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val request = input.request
  val stageable = input.stageable
  val excluded = input.excluded
  val restoreState = input.restoreState
  val foreignStagedPaths = input.foreignStagedPaths
  val foreignSnapshot = input.foreignSnapshot
  val branch = request.metadata.branch
  if (stageable.isEmpty()) {
    val foreignRestored = restoreForeignIndex(foreignStagedPaths, foreignSnapshot)
    if (foreignRestored != null) return blocked(foreignRestored)
    return publishExistingHead(request, branch, excluded)
  }
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
      ownedPaths = stageable,
      record = record,
    ),
  )
  val commitSha = when (val outcome = finalisationCommitSha(commit, stageable, restoreState)) {
    is FinalisationCommitShaBlocked -> return blocked(
      restoreForeignFinalisationIndex(outcome.reason, foreignStagedPaths, foreignSnapshot),
    )
    is FinalisationCommitShaReady -> outcome.value
  }
  val foreignRestored = restoreForeignIndex(foreignStagedPaths, foreignSnapshot)
  if (foreignRestored != null) return blocked(foreignRestored)
  val recordFailure = recordCommit(commitSha, stageable)
  return if (recordFailure != null) {
    FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
  } else {
    finalizeCommittedSubtask(
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
}

private fun FeatureTaskRuntimeSubtaskFinalisation.publishExistingHead(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  branch: String,
  excluded: List<String>,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val head = gitOperations.headCommitSha(repoRoot)
  val commitSha = head.value.orEmpty().trim()
  if (!head.ok || commitSha.isBlank()) {
    return blocked("HEAD could not be resolved (${head.error})")
  }
  val recordFailure = recordCommit(commitSha, emptyList())
  return if (recordFailure != null) {
    FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
  } else {
    finalizeCommittedSubtask(
      FinalizeCommittedSubtaskInput(
        request = request,
        branch = branch,
        stageable = emptyList(),
        excluded = excluded,
        commitSha = commitSha,
        rewrites = false,
      ),
    )
  }
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

fun FeatureTaskRuntimeSubtaskFinalisation.restoring(error: String, paths: List<String>, snapshot: String): String {
  val restored = gitOperations.restoreIndexState(repoRoot, paths, snapshot)
  return if (restored.ok) {
    "$error; the pre-finalisation index was restored and the working tree is unchanged"
  } else {
    "$error; the pre-finalisation index could NOT be restored (${restored.error}) — inspect " +
      "`git status` before committing anything yourself"
  }
}

fun FeatureTaskRuntimeSubtaskFinalisation.blocked(reason: String) = FeatureTaskRuntimeSubtaskFinalisationBlocked(
  "needs_human: subtask finalisation could not complete because $reason.",
)
