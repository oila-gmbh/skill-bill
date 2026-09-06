package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RepositoryOwnedPathsGitOperations {
  fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult
}

fun WorkflowGitOperations.repositoryOwnedPaths(repoRoot: Path): WorkflowGitOperationResult =
  repositoryOwnedPathsOperations.ownedPaths(repoRoot)
