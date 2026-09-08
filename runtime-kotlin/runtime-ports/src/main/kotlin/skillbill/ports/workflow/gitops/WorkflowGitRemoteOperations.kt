package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitRemoteOperations {
  companion object {
    const val ABSENT_REMOTE_BRANCH = "absent"
  }

  fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult.Failed(
    error = "This git operations implementation cannot push branch '$branch'.",
  )

  fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult.Failed(
    error = "This git operations implementation cannot push branch '$branch' under a lease.",
  )

  fun refreshRemoteBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = branch.trim())

  fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "false")
}
