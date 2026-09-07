package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface WorkflowGitOperations :
  WorkflowGitBranchOperations,
  WorkflowGitRemoteOperations,
  WorkflowGitCommitHistoryOperations,
  WorkflowGitWorktreeOperations {
  val checkpointHistoryOperations: CheckpointHistoryGitOperations

  val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations

  val repositoryFingerprintOperations: RepositoryFingerprintGitOperations

  val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations

  val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations

  val scopedStagingOperations: ScopedStagingGitOperations
}

fun WorkflowGitOperations.deleteCheckpointRefsUnderPrefix(
  repoRoot: Path,
  namespacePrefix: String,
  subtaskRefPrefix: String,
): WorkflowGitOperationResult {
  val listed = listCheckpointRefs(repoRoot, subtaskRefPrefix)
  if (listed !is WorkflowGitOperationResult.Ok) return listed
  val refs = listed.value.orEmpty()
    .split('\u0000')
    .filter(String::isNotBlank)
    .chunked(2)
    .mapNotNull { parts -> parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
  refs.forEach { refName ->
    val deleted = deleteCheckpointRef(repoRoot, namespacePrefix, refName)
    if (deleted !is WorkflowGitOperationResult.Ok) return deleted
  }
  return WorkflowGitOperationResult.Ok(value = refs.size.toString())
}

fun WorkflowGitOperations.amendHeadCommit(
  repoRoot: Path,
  expectedOwnedHeadSha: String,
  replacementMessage: String? = null,
  allowUnchangedIndex: Boolean = false,
): WorkflowGitOperationResult = checkpointHistoryOperations
  .amendHeadCommit(repoRoot, expectedOwnedHeadSha, replacementMessage, allowUnchangedIndex)

fun WorkflowGitOperations.headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
  checkpointHistoryOperations.headCommitMessage(repoRoot)

fun WorkflowGitOperations.updateCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
  targetSha: String,
): WorkflowGitOperationResult = checkpointHistoryOperations.updateRef(repoRoot, namespacePrefix, refName, targetSha)

fun WorkflowGitOperations.resolveCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
): WorkflowGitOperationResult = checkpointHistoryOperations.resolveRef(repoRoot, namespacePrefix, refName)

fun WorkflowGitOperations.listCheckpointRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult =
  checkpointHistoryOperations.listRefs(repoRoot, namespacePrefix)

fun WorkflowGitOperations.deleteCheckpointRef(
  repoRoot: Path,
  namespacePrefix: String,
  refName: String,
): WorkflowGitOperationResult = checkpointHistoryOperations.deleteRef(repoRoot, namespacePrefix, refName)
