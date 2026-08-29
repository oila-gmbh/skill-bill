@file:Suppress("TooManyFunctions") // single cohesive boundary: the git reads and writes a workflow phase needs

package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import java.nio.file.Path

internal const val HASH_RADIX_HEX: Int = 16
internal const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

@Suppress("TooManyFunctions") // single cohesive boundary: the git reads and writes a workflow phase needs
interface WorkflowGitOperations {
  fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String? = null): WorkflowGitOperationResult

  fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult

  fun currentBranch(repoRoot: Path): WorkflowGitOperationResult

  // Legacy repository-wide staging seam. Goal finalization and mid-run checkpoints stage explicit
  // path inventories through [stagePaths]; a repository-wide add cannot express workflow ownership.
  fun stageAll(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "")

  fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult

  /**
   * Pushes [branch] to `origin` (`git push -u origin <branch>`). Used by goal finalization after a
   * commit-all sweep so the opened PR tip includes remaining dirty work. Default refuses so adapters
   * without real remote push cannot pretend the tip was published.
   */
  fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot push branch '$branch'.",
  )

  /**
   * Pushes [branch] to `origin` under a lease: the push is refused when `origin/[branch]` no longer
   * holds the value this repository last observed. Reachable only when finalisation amends a subtask
   * commit that is already published, so a rewritten tip can never silently overwrite a remote a PR
   * or CI already references. Default refuses for the same reason [pushBranch] does.
   */
  fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot push branch '$branch' under a lease.",
  )

  /**
   * Whether [branch]'s local tip has commits not on `origin/[branch]`.
   * Value is `"true"` or `"false"`. Missing `origin/[branch]` counts as unpushed so a prior
   * commit-all whose push failed is re-pushed on the next finalize instead of being treated as done
   * merely because the worktree is clean. Default reports `"false"` so adapters without remotes do
   * not invent a push obligation.
   */
  fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "false")

  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  /**
   * Soft-resets HEAD to [commitSha], keeping the index and working tree. Used to roll back a
   * remediation checkpoint commit when the paired durable base record fails, so the branch ref and
   * the recorded base stay paired (both remain at the pre-commit state) rather than stranding an
   * unrecorded tip. Default refuses so adapters without real git cannot pretend the rollback worked.
   */
  fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot soft-reset HEAD to '$commitSha'.",
  )

  /**
   * True when [ancestorSha] is an ancestor of [descendantSha] (or the same commit). Used to detect a
   * recorded remediation base that the branch tip no longer contains.
   */
  fun isCommitAncestor(repoRoot: Path, ancestorSha: String, descendantSha: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(
      status = "error",
      error = "This git operations implementation cannot test commit ancestry.",
    )

  // Resolves an operator-supplied revision to a full commit SHA, or errors when it names no commit
  // in this repository. Default is a refusal so a store that cannot measure git never silently
  // accepts unverifiable evidence.
  fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot resolve commit '$revision'.",
  )

  fun validateBranchBase(repoRoot: Path, branch: String, expectedBaseBranch: String): WorkflowGitOperationResult

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult

  fun selectedDiffHunks(repoRoot: Path, request: WorkflowSelectedDiffHunksRequest): WorkflowSelectedDiffHunksResult
}

/**
 * SKILL-150: the scoped staging boundary a checkpoint commits through.
 *
 * A checkpoint owns an explicit path inventory, so it stages that inventory by literal pathspec and
 * captures the pre-checkpoint index so any staging or commit failure can be undone exactly. Every
 * listing here is NUL-delimited plumbing output: paths carrying spaces, quotes, or non-ASCII bytes
 * must round-trip unchanged, and porcelain's C-quoting would silently rewrite them.
 */
interface ScopedStagingGitOperations {
  /**
   * Stages exactly [paths] by literal pathspec, leaving every other index entry untouched. A path
   * absent from both the worktree and the index (for example a deletion an earlier attempt already
   * staged) has nothing left to stage and is skipped as a no-op rather than failing the staging.
   * An untracked path that `.gitignore` excludes is skipped the same way: naming it in the pathspec
   * makes `git add` refuse the whole inventory, even though a directory or bare `--all` would skip it.
   */
  fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult

  /**
   * Snapshot of the current index for [paths], as `git ls-files --stage -z` output. Paths absent
   * from the index are absent from the snapshot, which [restoreIndexState] reads as "remove".
   */
  fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult

  /**
   * Restores the index for [paths] to [snapshot] exactly, removing entries the snapshot does not
   * carry. The working tree is never touched: a restore recovers the index only.
   */
  fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult

  /** NUL-delimited repository-relative paths with staged index changes against HEAD. */
  fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult

  /**
   * Content identity of each of [paths] in the working tree, as NUL-delimited `<blob-sha>\t<path>`
   * records. Paths absent from the working tree are absent from the result, which reads as "gone".
   * Comparing two of these tells a checkpoint whether an owned file still holds the content the
   * phase left there, which no path listing can answer.
   */
  fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult
}

interface ScopedStagingGitOperationsProvider {
  val scopedStagingOperations: ScopedStagingGitOperations
}

// Silently staging nothing, or silently restoring nothing, both read downstream as success while the
// index is left in whatever partial state a failure produced. A checkpoint must never be able to
// reach that state, so an adapter without a real implementation refuses rather than degrades.
private object UnavailableScopedStagingGitOperations : ScopedStagingGitOperations {
  override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("stage an explicit owned-path inventory")

  override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("capture the pre-checkpoint index state")

  override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
    unavailable("restore the pre-checkpoint index state")

  override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult = unavailable("list staged paths")

  override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("read owned-path content identities")

  private fun unavailable(capability: String) = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot $capability; scoped checkpoints require a git adapter.",
  )
}

private fun WorkflowGitOperations.scopedStagingOperations(): ScopedStagingGitOperations =
  (this as? ScopedStagingGitOperationsProvider)?.scopedStagingOperations ?: UnavailableScopedStagingGitOperations

fun WorkflowGitOperations.stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().stagePaths(repoRoot, paths)

fun WorkflowGitOperations.captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().captureIndexState(repoRoot, paths)

fun WorkflowGitOperations.restoreIndexState(
  repoRoot: Path,
  paths: List<String>,
  snapshot: String,
): WorkflowGitOperationResult = scopedStagingOperations().restoreIndexState(repoRoot, paths, snapshot)

fun WorkflowGitOperations.stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
  scopedStagingOperations().stagedPaths(repoRoot)

fun WorkflowGitOperations.pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().pathContentIdentities(repoRoot, paths)

/**
 * SKILL-190: the amend and namespace-scoped ref primitives a per-subtask checkpoint history needs.
 *
 * Amending rewrites HEAD from whatever the index already carries; staging is the caller's job and
 * ownership is decided entirely from the caller-supplied [expectedOwnedHeadSha]. Ref operations are
 * confined to a caller-supplied namespace prefix so nothing here can move or delete a branch ref.
 */
