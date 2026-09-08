package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitRemoteOperations : WorkflowGitRemoteOperations {
  private const val NAME = "NoopWorkflowGitRemoteOperations"

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "pushBranch(repoRoot=$repoRoot, branch=$branch)")
    return WorkflowGitOperationResult(status = "ok", value = branch.trim())
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "localBranchHasUnpushedCommits(repoRoot=$repoRoot, branch=$branch)",
    )
    return WorkflowGitOperationResult(status = "ok", value = "false")
  }
}
