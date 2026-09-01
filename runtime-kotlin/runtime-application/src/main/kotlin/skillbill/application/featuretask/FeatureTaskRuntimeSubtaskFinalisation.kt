package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

internal data class FeatureTaskRuntimeCommitPushHandoff(
  val outcomeMessage: String,
  val changedPaths: List<String>,
)

internal sealed interface FeatureTaskRuntimeCommitPushHandoffResult

internal data class FeatureTaskRuntimeCommitPushHandoffValid(
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
) : FeatureTaskRuntimeCommitPushHandoffResult

internal data class FeatureTaskRuntimeCommitPushHandoffInvalid(
  val reason: String,
) : FeatureTaskRuntimeCommitPushHandoffResult

internal sealed interface FeatureTaskRuntimeSubtaskFinalisationResult

internal data class FeatureTaskRuntimeSubtaskFinalised(
  val commitSha: String,
  val stagedPaths: List<String>,
  val excludedSpecPaths: List<String>,
  val forcedWithLease: Boolean,
) : FeatureTaskRuntimeSubtaskFinalisationResult

internal data class FeatureTaskRuntimeSubtaskFinalisationBlocked(
  val reason: String,
) : FeatureTaskRuntimeSubtaskFinalisationResult

internal class FeatureTaskRuntimeSubtaskFinalisation(
  internal val gitOperations: WorkflowGitOperations,
  internal val repoRoot: Path,
  internal val record: (String) -> Unit,
  internal val recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
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
