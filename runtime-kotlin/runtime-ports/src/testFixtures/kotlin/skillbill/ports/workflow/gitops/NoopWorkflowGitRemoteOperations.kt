package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitRemoteOperations : WorkflowGitRemoteOperations {
  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = branch.trim())
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "false")
  }
}
