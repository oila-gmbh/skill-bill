package skillbill.ports.workflow

import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

private const val HASH_RADIX_HEX: Int = 16
private const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

interface WorkflowGitOperations {
  fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String? = null): WorkflowGitOperationResult

  fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult

  fun currentBranch(repoRoot: Path): WorkflowGitOperationResult

  fun stageAll(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "")

  fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult

  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  fun validateBranchBase(repoRoot: Path, branch: String, expectedBaseBranch: String): WorkflowGitOperationResult

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult

  fun selectedDiffHunks(repoRoot: Path, request: WorkflowSelectedDiffHunksRequest): WorkflowSelectedDiffHunksResult
}

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

interface GoalSubtaskReviewGitOperations {
  fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult

  fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult
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

private fun WorkflowGitOperations.reviewOperations(): GoalSubtaskReviewGitOperations =
  (this as? GoalSubtaskReviewGitOperationsProvider)?.goalSubtaskReviewOperations
    ?: UnavailableGoalSubtaskReviewGitOperations

object NoopWorkflowGitOperations : WorkflowGitOperations, GoalSubtaskReviewGitOperationsProvider {
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

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

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
}

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
}
