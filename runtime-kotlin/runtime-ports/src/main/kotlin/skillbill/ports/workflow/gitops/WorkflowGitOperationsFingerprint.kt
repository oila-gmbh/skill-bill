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

interface RepositoryFingerprintGitOperationsProvider {
  val repositoryFingerprintOperations: RepositoryFingerprintGitOperations
}

fun WorkflowGitOperations.repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
  (this as? RepositoryFingerprintGitOperationsProvider)
    ?.repositoryFingerprintOperations
    ?.repositoryFingerprint(repoRoot)
    ?: error("WorkflowGitOperations must provide a repository fingerprint implementation.")

fun WorkflowGitOperations.repositoryCheckpointFingerprint(
  repoRoot: Path,
  baseCommit: String?,
  headCommit: String,
  ownedPaths: List<String>,
): WorkflowGitOperationResult = (this as? RepositoryFingerprintGitOperationsProvider)
  ?.repositoryFingerprintOperations
  ?.repositoryCheckpointFingerprint(repoRoot, baseCommit, headCommit, ownedPaths)
  ?: error("WorkflowGitOperations must provide a repository checkpoint fingerprint implementation.")
