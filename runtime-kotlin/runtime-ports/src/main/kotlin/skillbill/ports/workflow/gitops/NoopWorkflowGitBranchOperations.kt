package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitBranchOperations : WorkflowGitBranchOperations {
  private const val NAME = "NoopWorkflowGitBranchOperations"

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "checkoutBranch(repoRoot=$repoRoot, branch=$branch)")
    return WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "branchExists(repoRoot=$repoRoot, branch=$branch)")
    return WorkflowGitOperationResult(status = "ok", value = "false")
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "currentBranch(repoRoot=$repoRoot)")
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "validateBranchBase(repoRoot=$repoRoot, branch=$branch, expectedBaseBranch=$expectedBaseBranch)",
    )
    return WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)
  }
}
