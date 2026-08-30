package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface CheckpointHistoryGitOperations {
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

interface CheckpointHistoryGitOperationsProvider {
  val checkpointHistoryOperations: CheckpointHistoryGitOperations
}
