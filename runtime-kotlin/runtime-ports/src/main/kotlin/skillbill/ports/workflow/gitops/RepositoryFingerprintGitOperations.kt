package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RepositoryFingerprintGitOperations {
  fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult

  fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)
}

fun WorkflowGitOperations.repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
  repositoryFingerprintOperations.repositoryFingerprint(repoRoot)

fun WorkflowGitOperations.repositoryCheckpointFingerprint(
  repoRoot: Path,
  baseCommit: String?,
  headCommit: String,
  ownedPaths: List<String>,
): WorkflowGitOperationResult =
  repositoryFingerprintOperations.repositoryCheckpointFingerprint(repoRoot, baseCommit, headCommit, ownedPaths)
