package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun gitBranchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  if (normalizedBranch.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
  }
  val args = listOf("rev-parse", "--verify", "--quiet", "refs/heads/$normalizedBranch")
  val existing = runGitProcess(repoRoot, args)
  return when {
    existing.timedOut -> WorkflowGitOperationResult(
      status = "error",
      error = gitTimedOutError(args),
    )
    existing.readFailure != null -> WorkflowGitOperationResult(
      status = "error",
      error = existing.readFailure.message.orEmpty(),
    )
    existing.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = "true")
    existing.exitCode == 1 -> WorkflowGitOperationResult(status = "ok", value = "false")
    else -> WorkflowGitOperationResult(
      status = "error",
      error = "git ${args.joinToString(" ")} failed with exit code ${existing.exitCode}: ${existing.output}",
    )
  }
}

internal fun gitCreateCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
  val commit = runGitCommand(repoRoot, "commit", "-m", message)
  return when {
    commit.ok -> runGitCommand(repoRoot, "rev-parse", "HEAD")
    commit.recordsNothingToCommit() -> WorkflowGitOperationResult(status = "ok", value = "")
    else -> commit
  }
}

internal fun gitPushBranch(repoRoot: Path, branch: String, withLease: Boolean): WorkflowGitOperationResult {
  val normalized = branch.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Branch name is required to push.")
  }
  val args = if (withLease) {
    listOf("push", "--force-with-lease", "-u", "origin", normalized)
  } else {
    listOf("push", "-u", "origin", normalized)
  }
  return runGitCommand(repoRoot, args).withValue(normalized)
}

internal fun gitLocalBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult {
  val normalized = branch.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Branch name is required to compare with origin.")
  }
  val remoteRef = "origin/$normalized"
  val remote = runGitCommand(repoRoot, "rev-parse", "--verify", remoteRef)
  if (!remote.ok) {
    return WorkflowGitOperationResult(status = "ok", value = "true")
  }
  val ahead = runGitCommand(repoRoot, "rev-list", "--count", "$remoteRef..$normalized")
  val count = ahead.value.trim().toIntOrNull()
  return when {
    !ahead.ok -> WorkflowGitOperationResult(
      status = "error",
      error = "Could not compare local '$normalized' to '$remoteRef': ${ahead.error}",
    )
    count == null -> WorkflowGitOperationResult(
      status = "error",
      error = "Could not parse unpushed commit count for '$normalized': '${ahead.value.trim()}'.",
    )
    else -> WorkflowGitOperationResult(status = "ok", value = if (count > 0) "true" else "false")
  }
}

internal fun gitResetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
  val normalized = commitSha.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "A commit SHA is required to soft-reset HEAD.")
  }
  return runGitCommand(repoRoot, "reset", "--soft", normalized)
}

internal fun gitIsCommitAncestor(
  repoRoot: Path,
  ancestorSha: String,
  descendantSha: String,
): WorkflowGitOperationResult {
  val ancestor = ancestorSha.trim()
  val descendant = descendantSha.trim()
  if (ancestor.isBlank() || descendant.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Ancestor and descendant commit SHAs are required.")
  }
  if (ancestor == descendant) {
    return WorkflowGitOperationResult(status = "ok", value = "true")
  }
  val args = listOf("merge-base", "--is-ancestor", ancestor, descendant)
  val result = runGitProcess(repoRoot, args)
  return when {
    result.timedOut -> WorkflowGitOperationResult(
      status = "error",
      error = gitTimedOutError(args),
    )
    result.readFailure != null -> WorkflowGitOperationResult(
      status = "error",
      error = result.readFailure.message.orEmpty(),
    )
    result.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = "true")
    result.exitCode == 1 -> WorkflowGitOperationResult(status = "ok", value = "false")
    else -> WorkflowGitOperationResult(
      status = "error",
      error = "git ${args.joinToString(" ")} failed with exit code ${result.exitCode}: ${result.output}",
    )
  }
}

internal fun gitResolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult {
  val normalized = revision.trim()
  if (normalized.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "A commit revision is required.")
  }
  val resolved = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", "$normalized^{commit}")
  return if (resolved.ok && !resolved.value.isNullOrBlank()) {
    resolved
  } else {
    WorkflowGitOperationResult(
      status = "error",
      error = "Revision '$normalized' does not name a commit in this repository.",
    )
  }
}

internal fun gitValidateBranchBase(
  repoRoot: Path,
  branch: String,
  expectedBaseBranch: String,
): WorkflowGitOperationResult {
  val normalizedBranch = branch.trim()
  val normalizedBase = expectedBaseBranch.trim()
  if (normalizedBranch.isBlank() || normalizedBase.isBlank()) {
    return WorkflowGitOperationResult(status = "error", error = "Branch and expected base branch are required.")
  }
  val result = runGitCommand(repoRoot, "merge-base", "--is-ancestor", normalizedBase, normalizedBranch)
  return if (result.ok) {
    WorkflowGitOperationResult(status = "ok", value = normalizedBase)
  } else {
    WorkflowGitOperationResult(
      status = "error",
      error = "Branch '$normalizedBranch' is not based on '$normalizedBase'. ${result.error}".trim(),
    )
  }
}
