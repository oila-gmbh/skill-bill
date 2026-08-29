@file:Suppress("TooManyFunctions") // single cohesive boundary: the git reads and writes a workflow phase needs

package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContentsResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

private const val HASH_RADIX_HEX: Int = 16
private const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

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
interface CheckpointHistoryGitOperations {
  /**
   * Rewrites HEAD from the current index, keeping its message unless [replacementMessage] is given,
   * and returns the new commit sha. Fails when HEAD is missing, when HEAD is not
   * [expectedOwnedHeadSha], or when the index carries nothing to commit.
   *
   * [allowUnchangedIndex] lifts only that last refusal, for the one caller that amends to replace a
   * message rather than to add content: subtask finalisation rewrites a provisional subject onto an
   * already-committed tree, and a clean tree there is the normal case, not a caller bug.
   */
  fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String? = null,
    allowUnchangedIndex: Boolean = false,
  ): WorkflowGitOperationResult

  /**
   * The full commit message of HEAD, for reading a runtime-written trailer back off branch history.
   * Fails when HEAD names no commit.
   */
  fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult

  /** Creates or moves [refName] (which must sit under [namespacePrefix]) to [targetSha]. */
  fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult

  /**
   * The commit sha [refName] points at, blank when the ref is genuinely absent, and a typed failure
   * only when the lookup itself could not run. A caller deciding whether a ref is free must be able to
   * tell "nothing is there" from "we could not find out".
   */
  fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult

  /** NUL-delimited `<objectname><NUL><refname>` records for every ref under [namespacePrefix]. */
  fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult

  /** Deletes [refName]; an already-absent ref is success, so a re-run of an interrupted prune passes. */
  fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult
}

interface CheckpointHistoryGitOperationsProvider {
  val checkpointHistoryOperations: CheckpointHistoryGitOperations
}

// Answering "ok" without amending or writing a ref reads downstream as a recorded checkpoint identity
// that does not exist, which no later read can recover. An adapter without real git refuses instead.
private object UnavailableCheckpointHistoryGitOperations : CheckpointHistoryGitOperations {
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

private fun WorkflowGitOperations.checkpointHistoryOperations(): CheckpointHistoryGitOperations =
  (this as? CheckpointHistoryGitOperationsProvider)?.checkpointHistoryOperations
    ?: UnavailableCheckpointHistoryGitOperations

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

/**
 * SKILL-190: deletes every ref under [subtaskRefPrefix], which must name
 * `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/`. An absent ref is success so an interrupted
 * prune or a second run converges on the same end state.
 */
fun WorkflowGitOperations.deleteCheckpointRefsUnderPrefix(
  repoRoot: Path,
  namespacePrefix: String,
  subtaskRefPrefix: String,
): WorkflowGitOperationResult {
  val listed = listCheckpointRefs(repoRoot, subtaskRefPrefix)
  if (!listed.ok) return listed
  val refs = listed.value.orEmpty()
    .split('\u0000')
    .filter(String::isNotBlank)
    .chunked(2)
    .mapNotNull { parts -> parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
  refs.forEach { refName ->
    val deleted = deleteCheckpointRef(repoRoot, namespacePrefix, refName)
    if (!deleted.ok) return deleted
  }
  return WorkflowGitOperationResult(status = "ok", value = refs.size.toString())
}

interface RepositoryFingerprintGitOperations {
  fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult

  /**
   * Fingerprints one workflow-owned comparison scope. Implementations must not allow unrelated
   * working-tree paths to influence this value.
   */
  fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)
}

interface RepositoryFingerprintGitOperationsProvider {
  val repositoryFingerprintOperations: RepositoryFingerprintGitOperations
}

// A porcelain-status fingerprint is both too coarse (a progressing repair run keeps emitting the same
// ` M path` listing) and unbounded against the durable fingerprint length bound, so there is no safe
// generic fallback: an adapter either contributes a real content fingerprint or this fails loudly.
fun WorkflowGitOperations.repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
  (this as? RepositoryFingerprintGitOperationsProvider)
    ?.repositoryFingerprintOperations
    ?.repositoryFingerprint(repoRoot)
    ?: error("WorkflowGitOperations must provide a repository fingerprint implementation.")

fun WorkflowGitOperations.repositoryCheckpointFingerprint(
  repoRoot: Path,
  baseCommit: String?,
  headCommit: String,
  ownedPaths: List<String>,
): WorkflowGitOperationResult = (this as? RepositoryFingerprintGitOperationsProvider)
  ?.repositoryFingerprintOperations
  ?.repositoryCheckpointFingerprint(repoRoot, baseCommit, headCommit, ownedPaths)
  ?: error("WorkflowGitOperations must provide a repository checkpoint fingerprint implementation.")

/**
 * SKILL-137: the working-tree paths a run owns, for the audit repository checkpoint.
 *
 * Deliberately NOT derived from `git status --porcelain`. Porcelain collapses a wholly-untracked
 * directory to a single `dir/` entry and C-quotes non-ASCII paths, while the goal-child baseline this
 * inventory is subtracted against is written with `ls-files --others --exclude-standard -z`. Comparing
 * the two representations silently fails to match, leaking a sibling subtask's new directory into the
 * child's audit scope (AC-014). Both sides therefore use the same NUL-delimited plumbing output.
 */
interface RepositoryOwnedPathsGitOperations {
  /** NUL-delimited repository-relative paths: untracked entries plus tracked worktree/index changes. */
  fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult
}

interface RepositoryOwnedPathsGitOperationsProvider {
  val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations
}

// An empty listing means "this scope owns nothing", which an audit reads as "no work was done here".
// A missing implementation must not be able to produce that answer, so it fails loudly exactly like
// the fingerprint helper above rather than degrading into an indistinguishable clean-tree result.
fun WorkflowGitOperations.repositoryOwnedPaths(repoRoot: Path): WorkflowGitOperationResult =
  (this as? RepositoryOwnedPathsGitOperationsProvider)?.repositoryOwnedPathsOperations
    ?.ownedPaths(repoRoot)
    ?: error("WorkflowGitOperations must provide a repository owned-paths implementation.")

interface RuntimePhaseFileManifestGitOperations {
  fun headCommit(repoRoot: Path): WorkflowGitOperationResult

  fun changedPathsBetweenCommits(repoRoot: Path, beforeCommit: String, afterCommit: String): WorkflowGitOperationResult
}

interface RuntimePhaseFileManifestGitOperationsProvider {
  val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations
}

fun WorkflowGitOperations.runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
  runtimePhaseFileManifestOperations().headCommit(repoRoot)

fun WorkflowGitOperations.runtimePhaseChangedPathsBetweenCommits(
  repoRoot: Path,
  beforeCommit: String,
  afterCommit: String,
): WorkflowGitOperationResult = runtimePhaseFileManifestOperations().changedPathsBetweenCommits(
  repoRoot,
  beforeCommit,
  afterCommit,
)

private fun WorkflowGitOperations.runtimePhaseFileManifestOperations(): RuntimePhaseFileManifestGitOperations =
  (this as? RuntimePhaseFileManifestGitOperationsProvider)?.runtimePhaseFileManifestOperations
    ?: NoopRuntimePhaseFileManifestGitOperations

private object NoopRuntimePhaseFileManifestGitOperations : RuntimePhaseFileManifestGitOperations {
  override fun headCommit(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun changedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "")
}

/**
 * Read-only validate-boundary evidence: scoped path contents at the current tree vs a base
 * ref, with rename detection so a moved file keeps its base identity.
 */
interface SuppressionEvidenceGitOperations {
  fun scopedPathContentsAgainstBase(
    repoRoot: Path,
    baseRef: String,
    headPaths: List<String>,
  ): WorkflowScopedPathContentsResult
}

interface SuppressionEvidenceGitOperationsProvider {
  val suppressionEvidenceOperations: SuppressionEvidenceGitOperations
}

fun WorkflowGitOperations.scopedPathContentsAgainstBase(
  repoRoot: Path,
  baseRef: String,
  headPaths: List<String>,
): WorkflowScopedPathContentsResult = (this as? SuppressionEvidenceGitOperationsProvider)?.suppressionEvidenceOperations
  ?.scopedPathContentsAgainstBase(repoRoot, baseRef, headPaths)
  ?: WorkflowScopedPathContentsResult(
    status = "error",
    error = "WorkflowGitOperations must provide a suppression-evidence implementation.",
  )

interface GoalSubtaskReviewGitOperations {
  fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult

  fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult

  fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
    status = "error",
    error = "Goal-subtask review baseline recovery is not supported by this git adapter.",
  )
}

interface GoalSubtaskReviewGitOperationsProvider {
  val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations
}

private object UnavailableGoalSubtaskReviewGitOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = "error",
      error = "Goal-subtask review baselines require a branch-aware git adapter.",
    )

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
    status = "error",
    error = "Goal-subtask review input requires a git adapter.",
  )

  override fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
    status = "error",
    error = "Goal-subtask review baseline recovery requires a git adapter.",
  )
}

fun WorkflowGitOperations.captureGoalSubtaskReviewBaseline(
  repoRoot: Path,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = reviewOperations().captureBaseline(repoRoot, expectedBranch)

fun WorkflowGitOperations.buildGoalSubtaskReviewInput(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalSubtaskReviewInputResult = reviewOperations().buildInput(repoRoot, baseline, expectedBranch)

fun WorkflowGitOperations.recoverGoalSubtaskReviewBaseline(
  repoRoot: Path,
  request: GoalSubtaskReviewBaselineRecoveryRequest,
  expectedBranch: String,
): GoalSubtaskReviewBaselineResult = reviewOperations().recoverBaseline(repoRoot, request, expectedBranch)

private fun WorkflowGitOperations.reviewOperations(): GoalSubtaskReviewGitOperations =
  (this as? GoalSubtaskReviewGitOperationsProvider)?.goalSubtaskReviewOperations
    ?: UnavailableGoalSubtaskReviewGitOperations

object NoopWorkflowGitOperations :
  WorkflowGitOperations,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "false")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "ok",
    value = "recorded:${message.hashCode().toUInt().toString(HASH_RADIX_HEX)}",
  )

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch.trim())

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "false")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = commitSha.trim())

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(
    status = "ok",
    value = if (ancestorSha.trim() == descendantSha.trim()) "true" else "true",
  )

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    changedFileSummary = GoalObservabilityChangedFileSummary(
      total = 0,
      added = 0,
      modified = 0,
      deleted = 0,
      renamed = 0,
      untracked = 0,
    ),
    diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(
    status = "ok",
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  )

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = NoopGoalSubtaskReviewGitOperations

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations =
    NoopRepositoryFingerprintGitOperations
}

private object NoopRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = NOOP_REPOSITORY_FINGERPRINT)
}

private const val NOOP_REPOSITORY_FINGERPRINT: String = "noop-repository-fingerprint"

private object NoopGoalSubtaskReviewGitOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
    if (expectedBranch.isBlank()) {
      GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = GoalSubtaskReviewBaseline(
          reviewBaseSha = "0".repeat(NOOP_REVIEW_BASE_SHA_LENGTH),
          baselineUntrackedPaths = emptyList(),
        ),
      )
    }

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
    status = "ok",
    input = GoalSubtaskReviewInput(
      reviewBaseSha = baseline.reviewBaseSha,
      currentHeadSha = baseline.reviewBaseSha,
      trackedDelta = "",
      ownedUntrackedPatches = "",
    ),
  )

  override fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult = if (expectedBranch.isBlank()) {
    GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
  } else {
    GoalSubtaskReviewBaselineResult(
      status = "ok",
      baseline = request.toRecoveredBaseline(request.unreachableSha),
    )
  }
}
