package skillbill.ports.workflow

import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

private const val HASH_RADIX_HEX: Int = 16

interface WorkflowGitOperations {
  fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String? = null): WorkflowGitOperationResult

  // Whether [branch] already exists in the repository, without creating it. value is "true"/"false"
  // on ok; callers must never treat an error result as existence. Used by branch re-attach to refuse
  // creating a second/divergent branch when the persisted branch is gone.
  fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult

  fun currentBranch(repoRoot: Path): WorkflowGitOperationResult

  fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult

  // Git-measured HEAD commit SHA, used as ground truth instead of an
  // agent-self-reported value.
  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  fun validateBranchBase(repoRoot: Path, branch: String, expectedBaseBranch: String): WorkflowGitOperationResult

  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult

  fun selectedDiffHunks(repoRoot: Path, request: WorkflowSelectedDiffHunksRequest): WorkflowSelectedDiffHunksResult
}

object NoopWorkflowGitOperations : WorkflowGitOperations {
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

  // No-op measures nothing, so the SHA fallback stays inert without a real adapter.
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
}
