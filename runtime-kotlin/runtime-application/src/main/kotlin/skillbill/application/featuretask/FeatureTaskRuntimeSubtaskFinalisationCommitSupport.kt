package skillbill.application.featuretask

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
