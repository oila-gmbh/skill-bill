package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface CheckpointHistoryGitOperations {
  fun createScopedCheckpoint(repoRoot: Path, paths: List<String>, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot create a retained scoped checkpoint.",
    )

  fun currentScopedContentFingerprint(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot fingerprint scoped content.",
    )

  fun retainedScopedContentFingerprint(repoRoot: Path, checkpointId: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot fingerprint retained content.",
    )

  fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String? = null,
    allowUnchangedIndex: Boolean = false,
  ): WorkflowGitOperationResult

  fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult

  fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult

  fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult

  fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult

  fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult
}
