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
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.ports.workflow.gitops.unstagePaths
import java.nio.file.Path

class FeatureTaskRuntimeSubtaskFinalisation(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val record: (String) -> Unit,
  internal val recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
) {
  fun finalise(request: FeatureTaskRuntimeSubtaskFinaliseRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    val dirty = gitOperations.dirtyImplementationPaths(repoRoot)
    return when (dirty) {
      is DirtyPathsError -> blocked(dirty.reason)
      is DirtyPaths -> finaliseDirtyPaths(request, dirty.paths)
    }
  }

  companion object {
    fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult =
      FeatureTaskRuntimeSubtaskFinalisationHandoff.readHandoff(envelope)

    fun withCommitSha(envelope: Map<String, Any?>, commitSha: String): Map<String, Any?> =
      FeatureTaskRuntimeSubtaskFinalisationHandoff.withCommitSha(envelope, commitSha)
  }
}

private sealed interface FinalisationCommitPreparationOutcome

private data class FinalisationCommitPreparationReady(
  val value: FinalisationCommitRequest,
) : FinalisationCommitPreparationOutcome

private data class FinalisationCommitPreparationBlocked(
  val reason: String,
) : FinalisationCommitPreparationOutcome

private fun FeatureTaskRuntimeSubtaskFinalisation.finaliseDirtyPaths(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  dirtyPaths: List<String>,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val paths = stageablePathsFrom(dirtyPaths)
  if (paths.excluded.isNotEmpty()) record(specExclusionRecord(request.identity, paths.excluded))
  return when (val prepared = prepareFinalisationCommit(request, paths)) {
    is FinalisationCommitPreparationBlocked -> blocked(prepared.reason)
    is FinalisationCommitPreparationReady -> commitAndPush(prepared.value)
  }
}

private fun FeatureTaskRuntimeSubtaskFinalisation.prepareFinalisationCommit(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  paths: StageablePathsOutcome,
): FinalisationCommitPreparationOutcome {
  val eligible = when (val eligibility = eligibleFinalisationPaths(request, paths.stageable)) {
    is FinalisationPathsBlocked -> return FinalisationCommitPreparationBlocked(eligibility.reason)
    is FinalisationPathsReady -> eligibility.paths
  }
  val staged = gitOperations.stagedPaths(repoRoot)
  if (!staged.ok) {
    return FinalisationCommitPreparationBlocked(
      "the pre-finalisation staged inventory could not be read (${staged.error})",
    )
  }
  return prepareFinalisationCommitAfterStaged(request, paths, eligible, staged.value.orEmpty())
}

private fun FeatureTaskRuntimeSubtaskFinalisation.prepareFinalisationCommitAfterStaged(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  paths: StageablePathsOutcome,
  eligible: List<String>,
  stagedOutput: String,
): FinalisationCommitPreparationOutcome {
  val stagedPaths = stagedOutput.split('\u0000')
    .map(::normalizeRepoPath)
    .filter(String::isNotBlank)
    .distinct()
  val eligiblePaths = eligible.map(::normalizeRepoPath).toSet()
  val foreignStaged = stagedPaths.filterNot { it in eligiblePaths }
  val foreignSnapshot = gitOperations.captureIndexState(repoRoot, foreignStaged)
  if (!foreignSnapshot.ok) {
    return FinalisationCommitPreparationBlocked(
      "the foreign staged index could not be captured (${foreignSnapshot.error})",
    )
  }
  val unstaged = gitOperations.unstagePaths(repoRoot, foreignStaged)
  if (!unstaged.ok) {
    return FinalisationCommitPreparationBlocked(
      restoring(unstaged.error, foreignStaged, foreignSnapshot.value.orEmpty()),
    )
  }
  if (eligible.isEmpty() && paths.excluded.isNotEmpty() && !ownedHeadAlreadyFinalised(request.durableCommitSha)) {
    return FinalisationCommitPreparationBlocked(emptyStageableReason(paths.excluded))
  }
  return prepareFinalisationCommitAfterForeignIndex(
    request,
    paths,
    eligible,
    foreignStaged,
    foreignSnapshot.value.orEmpty(),
  )
}

private fun FeatureTaskRuntimeSubtaskFinalisation.prepareFinalisationCommitAfterForeignIndex(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  paths: StageablePathsOutcome,
  eligible: List<String>,
  foreignStaged: List<String>,
  foreignSnapshot: String,
): FinalisationCommitPreparationOutcome {
  return when (val staging = prepareStaging(eligible)) {
    is FinalisationStagingReady -> FinalisationCommitPreparationReady(
      FinalisationCommitRequest(
        request,
        eligible,
        paths.excluded,
        staging.restoreState,
        foreignStaged,
        foreignSnapshot,
      ),
    )
    is FinalisationStagingBlocked -> {
      val restored = restoreForeignIndex(foreignStaged, foreignSnapshot)
      FinalisationCommitPreparationBlocked(
        restored?.let { "${staging.result.reason}; $it" } ?: staging.result.reason,
      )
    }
  }
}

internal sealed interface FinalisationStagingOutcome

internal data class FinalisationStagingReady(val restoreState: String) : FinalisationStagingOutcome

internal data class FinalisationStagingBlocked(
  val result: FeatureTaskRuntimeSubtaskFinalisationBlocked,
) : FinalisationStagingOutcome

private sealed interface FinalisationPathsOutcome

private data class FinalisationPathsReady(val paths: List<String>) : FinalisationPathsOutcome

private data class FinalisationPathsBlocked(val reason: String) : FinalisationPathsOutcome

private fun FeatureTaskRuntimeSubtaskFinalisation.eligibleFinalisationPaths(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  stageable: List<String>,
): FinalisationPathsOutcome {
  val owned = request.ownedPaths.map(::normalizeRepoPath).toSet()
  val declaredHistory = request.boundaryHistoryPaths.map(::normalizeRepoPath).toSet()
  val declaredHistoryRoots = request.boundaryHistoryRoots.map(::normalizeRepoPath).toSet()
  val unowned = stageable.filterNot { normalizeRepoPath(it) in owned }
  val unreviewed = stageable.filterNot {
    normalizeRepoPath(it) in owned || isBoundaryHistoryPath(it, declaredHistory, declaredHistoryRoots)
  }
  if (request.enforceReviewBoundary && unreviewed.isNotEmpty()) {
    record(
      "record_kind=refusal seam=FeatureTaskRuntimeSubtaskFinalisation.finalise " +
        "value_used='${unreviewed.joinToString(", ")}' value_expected=durable owned-path inventory and " +
        "declared boundary-history paths cause=post-review changes are not eligible for finalisation",
    )
    return FinalisationPathsBlocked(
      "the reviewed tree no longer covers changed paths ${unreviewed.joinToString(", ")}; source changes after " +
        "review must re-enter audit and review before finalisation",
    )
  }
  val ambiguous = unowned.filter { normalizeRepoPath(it) !in declaredHistory }
  if (request.enforceReviewBoundary && ambiguous.isNotEmpty()) {
    record(
      "record_kind=refusal seam=FeatureTaskRuntimeSubtaskFinalisation.finalise " +
        "value_used='${ambiguous.joinToString(", ")}' value_expected=proven subtask ownership " +
        "cause=foreign dirty content cannot enter the subtask commit",
    )
    return FinalisationPathsBlocked(
      "the durable subtask ownership inventory does not prove ownership of changed paths " +
        ambiguous.joinToString(", "),
    )
  }
  return FinalisationPathsReady(
    if (request.enforceReviewBoundary) {
      stageable.filter {
        normalizeRepoPath(it) in owned || isBoundaryHistoryPath(it, declaredHistory, declaredHistoryRoots)
      }
    } else {
      stageable
    },
  )
}

internal data class FinalisationCommitRequest(
  val request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  val stageable: List<String>,
  val excluded: List<String>,
  val restoreState: String,
  val foreignStagedPaths: List<String>,
  val foreignSnapshot: String,
)

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
