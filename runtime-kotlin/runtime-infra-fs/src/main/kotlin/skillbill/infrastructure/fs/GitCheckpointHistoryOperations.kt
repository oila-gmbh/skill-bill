package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitCheckpointHistoryOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult {
    val expected = expectedOwnedHeadSha.trim()
    val precondition = gitCheckpointProtectedBranchFailure(repoRoot)
      ?: gitCheckpointOwnedHeadFailure(repoRoot, expected)
      ?: if (allowUnchangedIndex) null else gitCheckpointStagedContentFailure(repoRoot, expected)
    precondition?.let { return it }
    val message = replacementMessage?.trim()
    val amendArgs = if (message.isNullOrBlank()) {
      listOf("commit", "--amend", "--no-edit")
    } else {
      listOf("commit", "--amend", "-m", message)
    }
    val amended = runGitCommand(repoRoot, amendArgs)
    if (!amended.ok) return amended
    return runGitCommand(repoRoot, "rev-parse", "HEAD")
  }

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult {
    val message = runGitCommand(repoRoot, "log", "-1", "--format=%B")
    if (!message.ok) return message
    return WorkflowGitOperationResult(status = "ok", value = message.value.orEmpty())
  }

  override fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val target = targetSha.trim()
    if (target.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "A target sha is required to write ref '$ref'.")
    }
    return runGitCommand(repoRoot, "update-ref", ref, target).withValue(ref)
  }

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val resolved = runGitCommand(repoRoot, "for-each-ref", "--format=%(objectname)", ref)
    if (!resolved.ok) {
      return WorkflowGitOperationResult(
        status = "error",
        error = "Ref '$ref' could not be looked up (${resolved.error}).",
      )
    }
    return WorkflowGitOperationResult(status = "ok", value = resolved.value.orEmpty().trim())
  }

  override fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult {
    val prefix = namespacePrefix.trim()
    if (prefix.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "A ref namespace prefix is required.")
    }
    return runGitCommand(
      repoRoot,
      "for-each-ref",
      "--format=%(objectname)%00%(refname)%00",
      prefix,
    )
  }

  override fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    if (!existing.ok || existing.value.orEmpty().isBlank()) {
      return WorkflowGitOperationResult(status = "ok", value = ref)
    }
    return runGitCommand(repoRoot, "update-ref", "-d", ref).withValue(ref)
  }
}
