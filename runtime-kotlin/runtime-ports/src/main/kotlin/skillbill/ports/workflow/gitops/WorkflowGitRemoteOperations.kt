package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitRemoteOperations {
  fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot push branch '$branch'.",
  )

  fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot push branch '$branch' under a lease.",
  )

  fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "false")
}
