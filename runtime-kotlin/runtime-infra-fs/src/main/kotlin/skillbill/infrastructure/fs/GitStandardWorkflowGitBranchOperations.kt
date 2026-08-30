package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.WorkflowGitBranchOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitStandardWorkflowGitBranchOperations : WorkflowGitBranchOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    gitCheckoutBranch(repoRoot, branch, baseBranch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    gitBranchExists(repoRoot, branch)

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "branch", "--show-current")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = gitValidateBranchBase(repoRoot, branch, expectedBaseBranch)
}
