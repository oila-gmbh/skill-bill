package skillbill.infrastructure.fs

import skillbill.ports.workflow.CheckpointHistoryGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Path

private val REF_NAME_REJECTED_SEGMENTS = setOf("", ".", "..")

/**
 * SKILL-190: amend and namespace-scoped ref plumbing for per-subtask checkpoint history.
 *
 * The amend path stages nothing: it commits whatever the index already carries, and refuses unless
 * HEAD is exactly the sha the caller declared it owns. Every ref operation validates the name against
 * the caller-supplied prefix before any git write, so a rejected name can never partially apply.
 */
internal object GitCheckpointHistoryOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
  ): WorkflowGitOperationResult {
    val expected = expectedOwnedHeadSha.trim()
    if (expected.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "An owned HEAD sha is required to amend.")
    }
    val head = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", "HEAD")
    val currentHead = head.value.orEmpty().trim()
    if (!head.ok || currentHead.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "HEAD does not name a commit; nothing to amend.")
    }
    if (currentHead != expected) {
      return WorkflowGitOperationResult(
        status = "error",
        error = "HEAD is '$currentHead' but the caller owns '$expected'; refusing to amend an unowned commit.",
      )
    }
    val staged = runGitProcess(repoRoot, listOf("diff", "--cached", "--quiet"))
    if (staged.timedOut || staged.readFailure != null) {
      return WorkflowGitOperationResult(
        status = "error",
        error = staged.readFailure?.message ?: "git diff --cached timed out after ${GIT_TIMEOUT_SECONDS}s.",
      )
    }
    if (staged.exitCode == 0) {
      return WorkflowGitOperationResult(
        status = "error",
        error = "The index carries no staged content; refusing to amend '$currentHead'.",
      )
    }
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

  override fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
    val target = targetSha.trim()
    if (target.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "A target sha is required to write ref '$ref'.")
    }
    return runGitCommand(repoRoot, "update-ref", ref, target).withValue(ref)
  }

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
    val resolved = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    val sha = resolved.value.orEmpty().trim()
    return if (resolved.ok && sha.isNotBlank()) {
      WorkflowGitOperationResult(status = "ok", value = sha)
    } else {
      WorkflowGitOperationResult(status = "error", error = "Ref '$ref' names nothing in this repository.")
    }
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
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    if (!existing.ok || existing.value.orEmpty().isBlank()) {
      return WorkflowGitOperationResult(status = "ok", value = ref)
    }
    return runGitCommand(repoRoot, "update-ref", "-d", ref).withValue(ref)
  }

  private fun validatedRef(namespacePrefix: String, refName: String): String? {
    val prefix = namespacePrefix.trim()
    val ref = refName.trim()
    if (prefix.isBlank() || ref.isBlank()) return null
    if (!ref.startsWith(prefix) || ref == prefix) return null
    if (ref.endsWith("/") || ref.endsWith(".lock")) return null
    val segments = ref.split('/')
    if (segments.any { it in REF_NAME_REJECTED_SEGMENTS || it.endsWith(".lock") || it.contains("..") }) return null
    if (ref.any { it.isWhitespace() || it.isISOControl() } || ref.contains('~') || ref.contains('^')) return null
    if (ref.contains(':') || ref.contains('?') || ref.contains('*') || ref.contains('[') || ref.contains("\\")) {
      return null
    }
    return ref
  }

  private fun rejected(namespacePrefix: String, refName: String) = WorkflowGitOperationResult(
    status = "error",
    error = "Ref '${refName.trim()}' is not a valid ref inside namespace '${namespacePrefix.trim()}'.",
  )
}
