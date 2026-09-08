package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import java.nio.file.Path

interface WorkflowGitWorktreeOperations {
  fun stageAll(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "")

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult

  fun selectedDiffHunks(repoRoot: Path, request: WorkflowSelectedDiffHunksRequest): WorkflowSelectedDiffHunksResult
}
