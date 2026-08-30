package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object UnavailableCheckpointHistoryGitOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult = unavailable("amend the HEAD commit")

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
    unavailable("read the HEAD commit message")

  override fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult = unavailable("write checkpoint ref '$refName'")

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult =
    unavailable("resolve checkpoint ref '$refName'")

  override fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult =
    unavailable("list checkpoint refs under '$namespacePrefix'")

  override fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult =
    unavailable("delete checkpoint ref '$refName'")

  private fun unavailable(capability: String) = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot $capability; checkpoint history requires a git adapter.",
  )
}

internal fun WorkflowGitOperations.checkpointHistoryOperations(): CheckpointHistoryGitOperations =
  (this as? CheckpointHistoryGitOperationsProvider)?.checkpointHistoryOperations
    ?: UnavailableCheckpointHistoryGitOperations
