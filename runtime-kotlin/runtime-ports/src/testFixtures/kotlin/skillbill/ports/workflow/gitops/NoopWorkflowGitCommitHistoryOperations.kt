package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitCommitHistoryOperations : WorkflowGitCommitHistoryOperations {
  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(
      value = "recorded:${message.hashCode().toUInt().toString(HASH_RADIX_HEX)}",
    )
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = commitSha.trim())
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(
      value = if (ancestorSha.trim() == descendantSha.trim()) "true" else "true",
    )
  }
}
