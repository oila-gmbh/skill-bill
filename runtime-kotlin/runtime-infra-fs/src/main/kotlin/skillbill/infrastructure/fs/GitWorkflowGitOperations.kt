package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitBranchOperations
import skillbill.ports.workflow.gitops.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.WorkflowGitWorktreeOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

class GitWorkflowGitOperations :
  WorkflowGitOperations,
  WorkflowGitBranchOperations by GitStandardWorkflowGitOperations,
  WorkflowGitRemoteOperations by GitStandardWorkflowGitOperations,
  WorkflowGitCommitHistoryOperations by GitStandardWorkflowGitOperations,
  WorkflowGitWorktreeOperations by GitStandardWorkflowGitOperations,
  SuppressionEvidenceGitOperationsProvider {
  override val checkpointHistoryOperations: CheckpointHistoryGitOperations = GitCheckpointHistoryOperations
  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = GitGoalSubtaskReviewOperations
  override val scopedStagingOperations: ScopedStagingGitOperations = GitScopedStagingOperations
  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    GitRuntimePhaseFileManifestOperations
  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations = GitRepositoryFingerprintOperations
  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations = GitRepositoryOwnedPathsOperations
  override val suppressionEvidenceOperations: SuppressionEvidenceGitOperations = GitSuppressionEvidenceOperations
}

/**
 * Untracked entries and tracked worktree/index changes, both NUL-delimited. `ls-files --others` is the
 * same command that writes the goal-child baseline, so the two inventories are directly comparable;
 * `diff --name-only -z HEAD` covers tracked edits, which no untracked listing reports.
 */
internal object GitRepositoryOwnedPathsOperations : RepositoryOwnedPathsGitOperations {
  override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult {
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    if (!untracked.ok) return untracked
    val tracked = runGitCommand(repoRoot, "diff", "--name-only", "-z", "HEAD")
    // A repository with no commits has no HEAD to diff against; the untracked listing is the whole
    // owned inventory there, so an unresolvable HEAD is not a failure.
    val trackedValue = tracked.value.takeIf { tracked.ok }.orEmpty()
    return WorkflowGitOperationResult(
      status = "ok",
      // Each -z listing terminates every entry with NUL, so the two blobs concatenate directly.
      value = untracked.value.orEmpty() + trackedValue,
    )
  }
}
