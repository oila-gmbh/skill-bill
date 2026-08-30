package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Path

private const val SCOPED_REVIEW_PATH_DELIMITER: Char = '\u0000'

/**
 * The one place a review-scoped baseline is built.
 *
 * A digest recorded by the producer and a digest recomputed by a consumer are comparable only when
 * both are measured over the same scope. Splitting that construction across the launch seam and the
 * staleness check is what let a foreign tracked edit or a post-baseline untracked file reopen a
 * settled capped review on every resume, so both sides call through here.
 */
internal object FeatureTaskRuntimeScopedReviewBaseline {
  fun untrackedExclusions(
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    resolved: FeatureTaskRuntimeResolvedBranch,
  ): List<String> {
    val current = gitOperations.repositoryOwnedPaths(repoRoot)
    // An unreadable listing cannot widen the exclusion set, so fall back to the durable baseline
    // rather than inventing a scope: the pre-existing behavior, never something looser.
    if (!current.ok) return resolved.baselineUntrackedPaths
    return reviewUntrackedExclusions(
      baselineUntrackedPaths = resolved.baselineUntrackedPaths,
      currentUntrackedPaths = current.value.orEmpty()
        .split(SCOPED_REVIEW_PATH_DELIMITER)
        .map(String::trim)
        .filter(String::isNotBlank),
      ownedPaths = resolved.workflowOwnedPaths,
    )
  }

  fun of(
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    resolved: FeatureTaskRuntimeResolvedBranch,
    reviewBaseSha: String,
  ): GoalSubtaskReviewBaseline = GoalSubtaskReviewBaseline(
    reviewBaseSha,
    untrackedExclusions(gitOperations, repoRoot, resolved),
    resolved.workflowOwnedPaths,
  )
}
