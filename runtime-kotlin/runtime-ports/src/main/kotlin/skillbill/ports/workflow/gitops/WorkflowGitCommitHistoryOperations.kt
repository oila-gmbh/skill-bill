package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitCommitHistoryOperations {
  fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult

  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot soft-reset HEAD to '$commitSha'.",
  )

  fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot test commit ancestry.",
  )

  fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot resolve commit '$revision'.",
  )
}
