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
