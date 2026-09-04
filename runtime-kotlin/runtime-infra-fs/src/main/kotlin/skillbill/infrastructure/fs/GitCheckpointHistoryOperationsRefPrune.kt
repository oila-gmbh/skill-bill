package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.ProtectedBranches
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun gitCheckpointProtectedBranchFailure(repoRoot: Path): WorkflowGitOperationResult? {
  val branch = runGitCommand(repoRoot, "branch", "--show-current")
  if (!branch.ok) {
    return WorkflowGitOperationResult(
      status = "error",
      error = "Could not read the current branch; refusing to amend. ${branch.error}".trim(),
    )
  }
  val protected = ProtectedBranches.protectedName(branch.value) ?: return null
  return WorkflowGitOperationResult(
    status = "error",
    error = "HEAD is on protected branch '$protected'; refusing to amend shared history.",
  )
}

internal fun gitCheckpointOwnedHeadFailure(repoRoot: Path, expected: String): WorkflowGitOperationResult? {
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

internal fun gitCheckpointStagedContentFailure(repoRoot: Path, currentHead: String): WorkflowGitOperationResult? {
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

internal fun gitCheckpointValidatedRef(namespacePrefix: String, refName: String): String? {
  val prefix = namespacePrefix.trim().removeSuffix("/")
  val ref = refName.trim()
  if (prefix.isBlank() || !ref.startsWith("$prefix/")) return null
  if (ref.endsWith("/") || ref.endsWith(".lock")) return null
  val segmentRejected = ref.split('/').any { segment ->
    segment in GIT_CHECKPOINT_REF_NAME_REJECTED_SEGMENTS || segment.endsWith(".lock") || segment.contains("..")
  }
  val charRejected = ref.any { char ->
    char.isWhitespace() || char.isISOControl() || char in GIT_CHECKPOINT_REF_NAME_REJECTED_CHARS
  }
  return ref.takeIf { !segmentRejected && !charRejected }
}

internal fun gitCheckpointRejectedRef(namespacePrefix: String, refName: String) = WorkflowGitOperationResult(
  status = "error",
  error = "Ref '${refName.trim()}' is not a valid ref inside namespace '${namespacePrefix.trim()}'.",
)

internal val GIT_CHECKPOINT_REF_NAME_REJECTED_SEGMENTS = setOf("", ".", "..")
internal val GIT_CHECKPOINT_REF_NAME_REJECTED_CHARS = setOf(':', '?', '*', '[', '\\', '~', '^')
