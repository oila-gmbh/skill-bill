package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

fun WorkflowGitOperations.amendHeadCommit(
  repoRoot: Path,
  expectedOwnedHeadSha: String,
  replacementMessage: String? = null,
  allowUnchangedIndex: Boolean = false,
): WorkflowGitOperationResult = checkpointHistoryOperations()
  .amendHeadCommit(repoRoot, expectedOwnedHeadSha, replacementMessage, allowUnchangedIndex)

fun WorkflowGitOperations.headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
  checkpointHistoryOperations().headCommitMessage(repoRoot)

fun WorkflowGitOperations.updateCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
  targetSha: String,
): WorkflowGitOperationResult = checkpointHistoryOperations().updateRef(repoRoot, namespacePrefix, refName, targetSha)

fun WorkflowGitOperations.resolveCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
): WorkflowGitOperationResult = checkpointHistoryOperations().resolveRef(repoRoot, namespacePrefix, refName)

fun WorkflowGitOperations.listCheckpointRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult =
  checkpointHistoryOperations().listRefs(repoRoot, namespacePrefix)

fun WorkflowGitOperations.deleteCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
): WorkflowGitOperationResult = checkpointHistoryOperations().deleteRef(repoRoot, namespacePrefix, refName)
