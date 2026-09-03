package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopWorkflowGitCommitHistoryOperations : WorkflowGitCommitHistoryOperations {
  private const val NAME = "NoopWorkflowGitCommitHistoryOperations"

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "createCommit(repoRoot=$repoRoot, message=$message)")
    return WorkflowGitOperationResult(
      status = "ok",
      value = "recorded:${message.hashCode().toUInt().toString(HASH_RADIX_HEX)}",
    )
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "headCommitSha(repoRoot=$repoRoot)")
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "resetSoftToCommit(repoRoot=$repoRoot, commitSha=$commitSha)")
    return WorkflowGitOperationResult(status = "ok", value = commitSha.trim())
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "isCommitAncestor(repoRoot=$repoRoot, ancestorSha=$ancestorSha, descendantSha=$descendantSha)",
    )
    return WorkflowGitOperationResult(
      status = "ok",
      value = if (ancestorSha.trim() == descendantSha.trim()) "true" else "true",
    )
  }
}
