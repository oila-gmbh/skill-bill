package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

/** The agent's half of the finalisation contract: the commit subject. `changedPaths` is advisory. */
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

/**
 * SKILL-190: the runtime half of `commit_push`.
 *
 * The agent supplies the commit subject; every git write below is the runtime's. Staging sweeps every
 * dirty non-ignored worktree path except governed `.feature-specs/` inputs (gitignored paths never
 * appear in porcelain status, so they stay out). Order is load-bearing: the handoff message is
 * validated before anything is staged, so a rejected finalisation leaves the repository exactly as
 * the agent left it; the message is applied in the same amend that stages the content, so no commit
 * ever reaches a pushed state carrying the provisional subject; and the sha is captured after that
 * amend, so the value threaded into the manifest is the final one rather than an intermediate.
 *
 * [recordCommit] persists the durable pointer to the commit just written and runs BEFORE the push,
 * returning a blocking reason when it cannot. Recording after the push left a failed push blocked with
 * HEAD at the finalisation commit while the pointer still named the pre-amend checkpoint sha, so the
 * re-entry resolved Create against a clean index and the subtask could never finish.
 */
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
    val alreadyCommitted = paths.stageable.isEmpty()
    if (alreadyCommitted && paths.excluded.isNotEmpty()) {
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
