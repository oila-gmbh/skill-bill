package skillbill.ports.workflow

import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val HASH_RADIX_HEX: Int = 16

interface WorkflowGitOperations {
  fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String? = null): WorkflowGitOperationResult

  fun currentBranch(repoRoot: Path): WorkflowGitOperationResult

  fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult

  fun validateBranchBase(repoRoot: Path, branch: String, expectedBaseBranch: String): WorkflowGitOperationResult

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult
}

object NoopWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "ok",
    value = "recorded:${message.hashCode().toUInt().toString(HASH_RADIX_HEX)}",
  )

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")
}
