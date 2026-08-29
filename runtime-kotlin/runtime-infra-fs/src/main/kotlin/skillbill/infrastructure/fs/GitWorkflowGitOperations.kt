package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperationsProvider
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperationsProvider
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import java.nio.file.Path

@Inject
class GitWorkflowGitOperations :
  WorkflowGitOperations by GitStandardWorkflowGitOperations,
  CheckpointHistoryGitOperationsProvider,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider,
  RepositoryOwnedPathsGitOperationsProvider,
  RuntimePhaseFileManifestGitOperationsProvider,
  ScopedStagingGitOperationsProvider,
  SuppressionEvidenceGitOperationsProvider {
  override val checkpointHistoryOperations: CheckpointHistoryGitOperations = GitCheckpointHistoryOperations
  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = GitGoalSubtaskReviewOperations
  override val scopedStagingOperations: ScopedStagingGitOperations = GitScopedStagingOperations
  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    GitRuntimePhaseFileManifestOperations
  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations = GitRepositoryFingerprintOperations
  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations = GitRepositoryOwnedPathsOperations
  override val suppressionEvidenceOperations: SuppressionEvidenceGitOperations = GitSuppressionEvidenceOperations
}

/**
 * Untracked entries and tracked worktree/index changes, both NUL-delimited. `ls-files --others` is the
 * same command that writes the goal-child baseline, so the two inventories are directly comparable;
 * `diff --name-only -z HEAD` covers tracked edits, which no untracked listing reports.
 */
internal object GitRepositoryOwnedPathsOperations : RepositoryOwnedPathsGitOperations {
  override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult {
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    if (!untracked.ok) return untracked
    val tracked = runGitCommand(repoRoot, "diff", "--name-only", "-z", "HEAD")
    // A repository with no commits has no HEAD to diff against; the untracked listing is the whole
    // owned inventory there, so an unresolvable HEAD is not a failure.
    val trackedValue = tracked.value.takeIf { tracked.ok }.orEmpty()
    return WorkflowGitOperationResult(
      status = "ok",
      // Each -z listing terminates every entry with NUL, so the two blobs concatenate directly.
      value = untracked.value.orEmpty() + trackedValue,
    )
  }
}

@Suppress("TooManyFunctions") // mirrors the WorkflowGitOperations boundary one-for-one
internal object GitStandardWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    val normalizedBranch = branch.trim()
    if (normalizedBranch.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required.")
    }
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", normalizedBranch)
    return if (existing.ok) {
      checkoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", normalizedBranch))
        .withValue(normalizedBranch)
    } else {
      val base = baseBranch?.trim().orEmpty()
      if (base.isBlank()) {
        checkoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", "-b", normalizedBranch))
          .withValue(normalizedBranch)
      } else {
        checkoutPreservingLocalChanges(repoRoot, listOf("checkout", "--merge", "-b", normalizedBranch, base))
          .withValue(normalizedBranch)
      }
    }
  }

  /**
   * Switches branches without letting the switch overwrite local work.
   *
   * `checkout --merge` refuses outright while anything is staged — `fatal: cannot continue with
   * staged changes` — so the index is cleared for the switch and the same paths are staged again
   * afterwards. Clearing it touches the index only: every worktree byte the caller wanted preserved
   * is still on disk for the merge to carry across, and the paths are restaged whether the checkout
   * succeeded or failed, so a refused switch does not silently unstage the caller's work.
   */
  private fun checkoutPreservingLocalChanges(repoRoot: Path, args: List<String>): WorkflowGitOperationResult {
    val existingConflictMarkers = conflictMarkerPaths(repoRoot)
    val previouslyStaged = stagedPaths(repoRoot)
    if (previouslyStaged.isNotEmpty()) {
      val cleared = runGitCommand(repoRoot, "reset", "--quiet")
      if (!cleared.ok) return cleared
    }
    val outcome = mergeCheckout(repoRoot, args, existingConflictMarkers)
    if (previouslyStaged.isEmpty()) return outcome
    val restaged = runGitCommand(repoRoot, listOf("add", "--all", "--") + previouslyStaged)
    return if (restaged.ok) outcome else restaged
  }

  private fun mergeCheckout(
    repoRoot: Path,
    args: List<String>,
    existingConflictMarkers: List<String>,
  ): WorkflowGitOperationResult {
    val checkout = runGitCommand(repoRoot, args)
    val paths = conflictMarkerPaths(repoRoot).filterNot(existingConflictMarkers::contains)
    if (paths.isEmpty()) return checkout
    val resolved = runGitCommand(repoRoot, listOf("checkout", "--theirs", "--") + paths)
    if (!resolved.ok) return checkout
    val staged = runGitCommand(repoRoot, listOf("add", "--all", "--") + paths)
    return if (staged.ok) {
      WorkflowGitOperationResult(status = "ok", value = checkout.value)
    } else {
      staged
    }
  }

  /** Paths the index carries ahead of HEAD; empty when there is no HEAD to compare against yet. */
  private fun stagedPaths(repoRoot: Path): List<String> {
    val staged = runGitCommand(repoRoot, "diff", "--cached", "--name-only", "-z", "HEAD")
    if (!staged.ok) return emptyList()
    return staged.value.split('\u0000').filter(String::isNotBlank)
  }

  private fun conflictMarkerPaths(repoRoot: Path): List<String> {
    val check = runGitForActivity(repoRoot, listOf("diff", "--check"))
    if (check.ok) return emptyList()
    val markerPattern = Regex("""^(.*):\d+: leftover conflict marker$""")
    return check.error.lineSequence()
      .mapNotNull { line -> markerPattern.matchEntire(line)?.groupValues?.get(1) }
      .distinct()
      .toList()
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
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

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "branch", "--show-current")

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "add", "-A")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    val commit = runGitCommand(repoRoot, "commit", "-m", message)
    return when {
      commit.ok -> runGitCommand(repoRoot, "rev-parse", "HEAD")
      commit.recordsNothingToCommit() -> WorkflowGitOperationResult(status = "ok", value = "")
      else -> commit
    }
  }

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    val normalized = branch.trim()
    if (normalized.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required to push.")
    }
    return runGitCommand(repoRoot, "push", "-u", "origin", normalized).withValue(normalized)
  }

  override fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    val normalized = branch.trim()
    if (normalized.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required to push.")
    }
    // Argument-less --force-with-lease leases against the remote-tracking ref, so a remote that moved
    // since this repository last observed it rejects the push instead of being overwritten.
    return runGitCommand(repoRoot, "push", "--force-with-lease", "-u", "origin", normalized).withValue(normalized)
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    val normalized = branch.trim()
    if (normalized.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Branch name is required to compare with origin.")
    }
    val remoteRef = "origin/$normalized"
    val remote = runGitCommand(repoRoot, "rev-parse", "--verify", remoteRef)
    if (!remote.ok) {
      // No published tip yet — local commits (including a finalize commit whose push failed) need push.
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

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
    val normalized = commitSha.trim()
    if (normalized.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "A commit SHA is required to soft-reset HEAD.")
    }
    return runGitCommand(repoRoot, "reset", "--soft", normalized)
  }

  override fun isCommitAncestor(
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

  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult {
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

  override fun validateBranchBase(
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

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    // `-uall` expands untracked directories to their files. Default porcelain collapses them to
    // `?? dir/`, which finalisation then cannot stage through the regular-file path filter.
    runGitCommand(repoRoot, "status", "--porcelain", "-uall")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    GitRepositoryFingerprintOperations.worktreeActivity(repoRoot)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = GitRepositoryFingerprintOperations.selectedDiffHunks(repoRoot, request)
}
