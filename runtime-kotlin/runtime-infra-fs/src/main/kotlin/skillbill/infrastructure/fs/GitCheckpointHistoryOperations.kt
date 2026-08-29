package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private val REF_NAME_REJECTED_SEGMENTS = setOf("", ".", "..")
private val REF_NAME_REJECTED_CHARS = setOf(':', '?', '*', '[', '\\', '~', '^')

/**
 * SKILL-190: amend and namespace-scoped ref plumbing for per-subtask checkpoint history.
 *
 * The amend path stages nothing: it commits whatever the index already carries, and refuses unless
 * HEAD is exactly the sha the caller declared it owns. Every ref operation validates the name against
 * the caller-supplied prefix before any git write, so a rejected name can never partially apply.
 */
@Suppress("TooManyFunctions")
internal object GitCheckpointHistoryOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult {
    val expected = expectedOwnedHeadSha.trim()
    ownedHeadFailure(repoRoot, expected)?.let { return it }
    if (!allowUnchangedIndex) {
      stagedContentFailure(repoRoot, expected)?.let { return it }
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

  private fun ownedHeadFailure(repoRoot: Path, expected: String): WorkflowGitOperationResult? {
    if (expected.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "An owned HEAD sha is required to amend.")
    }
    val head = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", "HEAD")
    val currentHead = head.value.orEmpty().trim()
    if (!head.ok || currentHead.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "HEAD does not name a commit; nothing to amend.")
    }
    if (currentHead == expected) return null
    return WorkflowGitOperationResult(
      status = "error",
      error = "HEAD is '$currentHead' but the caller owns '$expected'; refusing to amend an unowned commit.",
    )
  }

  private fun stagedContentFailure(repoRoot: Path, currentHead: String): WorkflowGitOperationResult? {
    val staged = runGitProcess(repoRoot, listOf("diff", "--cached", "--quiet"))
    if (staged.timedOut || staged.readFailure != null) {
      return WorkflowGitOperationResult(
        status = "error",
        error = staged.readFailure?.message ?: gitTimedOutError(listOf("diff", "--cached")),
      )
    }
    if (staged.exitCode != 0) return null
    return WorkflowGitOperationResult(
      status = "error",
      error = "The index carries no staged content; refusing to amend '$currentHead'.",
    )
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
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
    val target = targetSha.trim()
    if (target.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "A target sha is required to write ref '$ref'.")
    }
    return runGitCommand(repoRoot, "update-ref", ref, target).withValue(ref)
  }

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
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
    val ref = validatedRef(namespacePrefix, refName) ?: return rejected(namespacePrefix, refName)
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    if (!existing.ok || existing.value.orEmpty().isBlank()) {
      return WorkflowGitOperationResult(status = "ok", value = ref)
    }
    return runGitCommand(repoRoot, "update-ref", "-d", ref).withValue(ref)
  }

  private fun validatedRef(namespacePrefix: String, refName: String): String? {
    val prefix = namespacePrefix.trim().removeSuffix("/")
    val ref = refName.trim()
    if (prefix.isBlank() || !ref.startsWith("$prefix/")) return null
    if (ref.endsWith("/") || ref.endsWith(".lock")) return null
    val segmentRejected = ref.split('/').any { segment ->
      segment in REF_NAME_REJECTED_SEGMENTS || segment.endsWith(".lock") || segment.contains("..")
    }
    val charRejected = ref.any { char ->
      char.isWhitespace() || char.isISOControl() || char in REF_NAME_REJECTED_CHARS
    }
    return ref.takeIf { !segmentRejected && !charRejected }
  }

  private fun rejected(namespacePrefix: String, refName: String) = WorkflowGitOperationResult(
    status = "error",
    error = "Ref '${refName.trim()}' is not a valid ref inside namespace '${namespacePrefix.trim()}'.",
  )
}
