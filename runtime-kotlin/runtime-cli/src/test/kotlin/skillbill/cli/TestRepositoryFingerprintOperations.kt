package skillbill.cli

import skillbill.ports.workflow.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object TestRepositoryFingerprintOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-repository-fingerprint")
}

// A measurable, empty inventory: the CLI fakes run against no real worktree, so the scope genuinely
// owns nothing. The helper exists because an absent implementation now fails loudly rather than
// silently reporting that same empty answer.
internal object TestRepositoryOwnedPathsOperations : RepositoryOwnedPathsGitOperations {
  override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")
}
