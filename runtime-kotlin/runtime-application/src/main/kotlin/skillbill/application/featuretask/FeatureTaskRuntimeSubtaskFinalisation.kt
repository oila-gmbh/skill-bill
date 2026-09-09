package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.captureIndexState
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
  val unowned = stageable.filterNot {
    isEligibleFinalisationPath(it, owned, declaredHistory, declaredHistoryRoots)
  }
  if (request.enforceReviewBoundary && unowned.isNotEmpty()) {
    record(
      "record_kind=refusal seam=FeatureTaskRuntimeSubtaskFinalisation.finalise " +
        "value_used='${unowned.joinToString(", ")}' value_expected=proven subtask ownership " +
        "cause=foreign dirty content cannot enter the subtask commit",
    )
    return FinalisationPathsBlocked(
      "the durable subtask ownership inventory does not prove ownership of changed paths " +
        unowned.joinToString(", "),
    )
  }
  return FinalisationPathsReady(
    if (request.enforceReviewBoundary) {
      stageable.filter { isEligibleFinalisationPath(it, owned, declaredHistory, declaredHistoryRoots) }
    } else {
      stageable
    },
  )
}

private fun isEligibleFinalisationPath(
  path: String,
  owned: Set<String>,
  declaredHistory: Set<String>,
  declaredHistoryRoots: Set<String>,
): Boolean {
  val normalized = normalizeRepoPath(path)
  return normalized in owned ||
    isGovernedSpecPath(normalized) ||
    isBoundaryHistoryPath(normalized, declaredHistory, declaredHistoryRoots)
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
