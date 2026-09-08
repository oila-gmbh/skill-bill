package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalised
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.stagePaths
import java.nio.file.Path

class FeatureTaskRuntimeSubtaskFinalisation(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val record: (String) -> Unit,
  val recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
) {
  fun finalise(request: FeatureTaskRuntimeSubtaskFinaliseRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    val dirtyOrError = gitOperations.dirtyImplementationPaths(repoRoot)
    if (dirtyOrError is DirtyPathsError) return blocked(dirtyOrError.reason)
    val paths = stageablePathsFrom((dirtyOrError as DirtyPaths).paths)
    if (paths.excluded.isNotEmpty()) record(specExclusionRecord(request.identity, paths.excluded))
    if (
      paths.stageable.isEmpty() &&
      paths.excluded.isNotEmpty() &&
      !ownedHeadAlreadyFinalised(request.durableCommitSha)
    ) {
      return blocked(emptyStageableReason(paths.excluded))
    }
    val staging = when (val outcome = prepareStaging(paths.stageable)) {
      is FinalisationStagingBlocked -> return outcome.result
      is FinalisationStagingReady -> outcome
    }
    return commitAndPush(request, paths.stageable, paths.excluded, staging.restoreState)
  }

  companion object {
    fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult =
      FeatureTaskRuntimeSubtaskFinalisationHandoff.readHandoff(envelope)

    fun withCommitSha(envelope: Map<String, Any?>, commitSha: String): Map<String, Any?> =
      FeatureTaskRuntimeSubtaskFinalisationHandoff.withCommitSha(envelope, commitSha)
  }
}

internal sealed interface FinalisationStagingOutcome

internal data class FinalisationStagingReady(val restoreState: String) : FinalisationStagingOutcome

internal data class FinalisationStagingBlocked(
  val result: FeatureTaskRuntimeSubtaskFinalisationBlocked,
) : FinalisationStagingOutcome

internal fun FeatureTaskRuntimeSubtaskFinalisation.prepareStaging(stageable: List<String>): FinalisationStagingOutcome {
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

fun FeatureTaskRuntimeSubtaskFinalisation.commitAndPush(
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
