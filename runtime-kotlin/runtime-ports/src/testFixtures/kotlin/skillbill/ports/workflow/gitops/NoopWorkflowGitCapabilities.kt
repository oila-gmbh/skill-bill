package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal const val HASH_RADIX_HEX: Int = 16
internal const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

object UnavailableScopedStagingGitOperations : ScopedStagingGitOperations {
  override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("stage an explicit owned-path inventory")

  override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("capture the pre-checkpoint index state")

  override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
    unavailable("restore the pre-checkpoint index state")

  override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult = unavailable("list staged paths")

  override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("read owned-path content identities")

  private fun unavailable(capability: String) = WorkflowGitOperationResult.Failed(
    error = "This git operations implementation cannot $capability; scoped checkpoints require a git adapter.",
  )
}

object NoopRuntimePhaseFileManifestGitOperations : RuntimePhaseFileManifestGitOperations {
  override fun headCommit(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "")

  override fun changedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")
}

object UnavailableRepositoryOwnedPathsGitOperations : RepositoryOwnedPathsGitOperations {
  override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult =
    error("WorkflowGitOperations must provide a repository owned-paths implementation.")
}

object UnavailableRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    error("WorkflowGitOperations must provide a repository fingerprint implementation.")

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult =
    error("WorkflowGitOperations must provide a repository checkpoint fingerprint implementation.")
}
