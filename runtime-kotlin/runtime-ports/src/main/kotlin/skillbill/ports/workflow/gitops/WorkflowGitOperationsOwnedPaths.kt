package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RepositoryOwnedPathsGitOperations {
  fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult
}

interface RepositoryOwnedPathsGitOperationsProvider {
  val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations
}

fun WorkflowGitOperations.repositoryOwnedPaths(repoRoot: Path): WorkflowGitOperationResult =
  (this as? RepositoryOwnedPathsGitOperationsProvider)?.repositoryOwnedPathsOperations
    ?.ownedPaths(repoRoot)
    ?: error("WorkflowGitOperations must provide a repository owned-paths implementation.")
