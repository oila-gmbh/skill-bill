package skillbill.engine

import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperationsTestBase
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

private const val COMMITTED_HEAD_SHA = "ffffffffffffffffffffffffffffffffffffffff"

class RecordingWorkflowGitOperations(
  var currentBranchValue: String = "feat/existing-runtime-branch",
  var currentBranchResult: WorkflowGitOperationResult? = null,
  var checkoutResult: WorkflowGitOperationResult? = null,
  var landedBranchAfterCheckout: String? = null,
  var existingBranches: Set<String>? = null,
  var branchExistsResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperationsTestBase() {
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
  val runtimePhaseHeadCommitSequence = ArrayDeque<String>()
  var changedPathsBetweenCommitsValue: String = ""
  var worktreeStatusValue: String = " M src/Foo.kt"
  var worktreeStatusResult: WorkflowGitOperationResult? = null
  val worktreeStatusSequence = ArrayDeque<String>()
  var ownedPathsValue: List<String> = emptyList()
  var ownedPathsResult: WorkflowGitOperationResult? = null
  val repositoryFingerprintSequence = ArrayDeque<String>()
  var repositoryFingerprintValue: String? = null
  var repositoryFingerprintCalls: Int = 0
  val createCommitMessages = mutableListOf<String>()
  var createCommitResult: WorkflowGitOperationResult? = null
  var localBranchHasUnpushedCommitsValue: Boolean = true
  var headCommitMessageValue: String = ""
  val amendCommitMessages = mutableListOf<String>()
  var amendHeadCommitResult: WorkflowGitOperationResult? = null
  val checkpointRefs = mutableMapOf<String, String>()
  val updateCheckpointRefCalls = mutableListOf<Pair<String, String>>()
  var updateCheckpointRefResult: WorkflowGitOperationResult? = null
  var resolveCheckpointRefResult: WorkflowGitOperationResult? = null
  var onResolveCheckpointRef: ((String) -> WorkflowGitOperationResult?)? = null
  var onResolveCommit: ((String) -> WorkflowGitOperationResult?)? = null
  var invalidShaOnRemediationCommit: Boolean = false
  val resetSoftToCommitCalls = mutableListOf<String>()
  var resetSoftToCommitResult: WorkflowGitOperationResult? = null
  val nonAncestorPairs = mutableSetOf<Pair<String, String>>()
  val stagePathsCalls = mutableListOf<String>()
  var stagePathsResult: WorkflowGitOperationResult? = null
  var indexSnapshotValue: String = ""
  var captureIndexStateResult: WorkflowGitOperationResult? = null
  val restoreIndexStateCalls = mutableListOf<String>()
  var restoreIndexStateResult: WorkflowGitOperationResult? = null
  val contentIdentities = mutableMapOf<String, String>()
  var onStagedPathsRead: (() -> Unit)? = null
  var stagedPathsValue: List<String> = emptyList()
  var stagedPathsResult: WorkflowGitOperationResult? = null
  val goalReviewBuildInputs = mutableListOf<GoalSubtaskReviewBaseline>()
  val goalReviewBuildResults = ArrayDeque<GoalSubtaskReviewInputResult>()
  var goalReviewTrackedDelta: String = ""
  var goalReviewRecoveredBaseline: GoalSubtaskReviewBaseline? = null
  var goalReviewRecoverCalls: Int = 0
  val goalReviewRecoverRequests =
    mutableListOf<GoalSubtaskReviewBaselineRecoveryRequest>()

  data class CheckoutCall(val branch: String, val baseBranch: String?)

  val checkoutCalls = mutableListOf<CheckoutCall>()
  val branchExistsCalls = mutableListOf<String>()
  var currentBranchCalls: Int = 0

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkoutCalls += CheckoutCall(branch, baseBranch)
    val result = checkoutResult ?: WorkflowGitOperationResult.Ok(value = branch)
    if (result is WorkflowGitOperationResult.Ok) {
      currentBranchValue = landedBranchAfterCheckout ?: branch
    }
    return result
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    branchExistsCalls += branch
    branchExistsResult?.let { return it }
    val exists = existingBranches?.contains(branch.trim()) ?: true
    return WorkflowGitOperationResult.Ok(value = exists.toString())
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    currentBranchCalls++
    return currentBranchResult ?: WorkflowGitOperationResult.Ok(value = currentBranchValue)
  }
  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    createCommitMessages += message
    if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
      val bogus = "not-a-valid-commit-sha"
      headCommitShaValue = bogus
      return WorkflowGitOperationResult.Ok(value = bogus)
    }
    val result = createCommitResult
      ?: WorkflowGitOperationResult.Ok(value = createCommitMessages.size.toString(16).padStart(40, '0'))
    if (result is WorkflowGitOperationResult.Ok && result.value.isNotBlank()) {
      headCommitShaValue = result.value.trim()
      headCommitMessageValue = message
    }
    return result
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = localBranchHasUnpushedCommitsValue.toString())

  override val checkpointHistoryOperations: CheckpointHistoryGitOperations =
    object : CheckpointHistoryGitOperations {
      override fun amendHeadCommit(
        repoRoot: Path,
        expectedOwnedHeadSha: String,
        replacementMessage: String?,
        allowUnchangedIndex: Boolean,
      ): WorkflowGitOperationResult {
        amendHeadCommitResult?.let { return it }
        if (expectedOwnedHeadSha.trim() != headCommitShaValue.trim()) {
          return WorkflowGitOperationResult.Failed(
            error = "HEAD is '$headCommitShaValue' but the caller owns '$expectedOwnedHeadSha'.",
          )
        }
        replacementMessage?.let { message ->
          amendCommitMessages += message
          if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
            createCommitMessages += message
            val bogus = "not-a-valid-commit-sha"
            headCommitShaValue = bogus
            return WorkflowGitOperationResult.Ok(value = bogus)
          }
        }
        headCommitShaValue = "a${amendCommitMessages.size.toString(16)}".padStart(40, '0')
        headCommitMessageValue = replacementMessage ?: headCommitMessageValue
        return WorkflowGitOperationResult.Ok(value = headCommitShaValue)
      }

      override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
        WorkflowGitOperationResult.Ok(value = headCommitMessageValue)

      override fun updateRef(
        repoRoot: Path,
        namespacePrefix: String,
        refName: String,
        targetSha: String,
      ): WorkflowGitOperationResult {
        updateCheckpointRefCalls += refName to targetSha
        updateCheckpointRefResult?.let { return it }
        checkpointRefs[refName] = targetSha
        return WorkflowGitOperationResult.Ok(value = refName)
      }

      override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult =
        onResolveCheckpointRef?.invoke(refName)
          ?: resolveCheckpointRefResult
          ?: WorkflowGitOperationResult.Ok(value = checkpointRefs[refName].orEmpty())

      override fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult =
        WorkflowGitOperationResult.Ok(
          value = checkpointRefs.entries.joinToString("") { (ref, sha) -> "$sha\u0000$ref\u0000" },
        )

      override fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
        checkpointRefs.remove(refName)
        return WorkflowGitOperationResult.Ok(value = refName)
      }
    }

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
    resetSoftToCommitCalls += commitSha.trim()
    val result = resetSoftToCommitResult ?: WorkflowGitOperationResult.Ok(value = commitSha.trim())
    if (result is WorkflowGitOperationResult.Ok) {
      headCommitShaValue = commitSha.trim()
    }
    return result
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    val ancestor = ancestorSha.trim()
    val descendant = descendantSha.trim()
    if (ancestor.isBlank() || descendant.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "Ancestor and descendant required.")
    }
    val reachable = ancestor == descendant || (ancestor to descendant) !in nonAncestorPairs
    return WorkflowGitOperationResult.Ok(value = if (reachable) "true" else "false")
  }

  var headCommitShaCalls: Int = 0

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    headCommitShaCalls++
    return headCommitShaResult ?: WorkflowGitOperationResult.Ok(value = headCommitShaValue)
  }

  val pushedBranches: MutableList<String> = mutableListOf()
  val leasePushedBranches: MutableList<String> = mutableListOf()
  var pushBranchResult: WorkflowGitOperationResult? = null

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    pushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    leasePushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult =
    onResolveCommit?.invoke(revision)
      ?: if (revision.startsWith("origin/")) {
        WorkflowGitOperationResult.Failed(
          error = "Revision '$revision' does not name a commit in this repository.",
        )
      } else {
        WorkflowGitOperationResult.Ok(
          value = revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) } ?: COMMITTED_HEAD_SHA,
        )
      }

  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    object : RuntimePhaseFileManifestGitOperations {
      override fun headCommit(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(
        value = runtimePhaseHeadCommitSequence.removeFirstOrNull().orEmpty(),
      )

      override fun changedPathsBetweenCommits(
        repoRoot: Path,
        beforeCommit: String,
        afterCommit: String,
      ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(
        value = if (beforeCommit == afterCommit) "" else changedPathsBetweenCommitsValue,
      )
    }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    worktreeStatusResult ?: WorkflowGitOperationResult.Ok(
      value = worktreeStatusSequence.removeFirstOrNull() ?: worktreeStatusValue,
    )

  override val scopedStagingOperations: ScopedStagingGitOperations =
    object : ScopedStagingGitOperations {
      override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
        stagePathsCalls += paths
        return stagePathsResult ?: WorkflowGitOperationResult.Ok(value = "")
      }

      override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
        captureIndexStateResult ?: WorkflowGitOperationResult.Ok(value = indexSnapshotValue)

      override fun restoreIndexState(
        repoRoot: Path,
        paths: List<String>,
        snapshot: String,
      ): WorkflowGitOperationResult {
        restoreIndexStateCalls += snapshot
        return restoreIndexStateResult ?: WorkflowGitOperationResult.Ok(value = "")
      }

      override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult {
        onStagedPathsRead?.invoke()
        return stagedPathsResult ?: WorkflowGitOperationResult.Ok(
          value = stagedPathsValue.joinToString(separator = "") { "$it\u0000" },
        )
      }

      override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
        WorkflowGitOperationResult.Ok(
          value = paths.joinToString(separator = "\u0000") { path ->
            "${contentIdentities[path] ?: "identity"}\t$path"
          },
        )
    }

  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations =
    object : RepositoryOwnedPathsGitOperations {
      override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult = ownedPathsResult
        ?: WorkflowGitOperationResult.Ok(
          value = ownedPathsValue.joinToString(separator = "") { "$it\u0000" },
        )
    }

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations =
    object : RepositoryFingerprintGitOperations {
      override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
        repositoryFingerprintCalls += 1
        return WorkflowGitOperationResult.Ok(
          value = repositoryFingerprintSequence.removeFirstOrNull()
            ?: repositoryFingerprintValue
            ?: "repository-fingerprint-$repositoryFingerprintCalls",
        )
      }

      override fun repositoryCheckpointFingerprint(
        repoRoot: Path,
        baseCommit: String?,
        headCommit: String,
        ownedPaths: List<String>,
      ): WorkflowGitOperationResult {
        repositoryFingerprintCalls += 1
        val scopeHash = listOf(
          baseCommit.orEmpty(),
          headCommit,
          ownedPaths.distinct().sorted().joinToString("\u0000"),
        ).joinToString("\u0000").hashCode().toUInt().toString(16)
        return WorkflowGitOperationResult.Ok(
          value = repositoryFingerprintSequence.removeFirstOrNull()
            ?: repositoryFingerprintValue
            ?: "repository-checkpoint-$scopeHash",
        )
      }
    }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = WorkflowGitOperationStatus.OK,
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
    status = WorkflowGitOperationStatus.OK,
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  )

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String) = GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      )

      override fun buildInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult {
        goalReviewBuildInputs += baseline
        return goalReviewBuildResults.removeFirstOrNull() ?: GoalSubtaskReviewInputResult(
          status = WorkflowGitOperationStatus.OK,
          input = GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = baseline.reviewBaseSha,
            trackedDelta = goalReviewTrackedDelta,
            ownedUntrackedPatches = "",
          ),
        )
      }

      override fun recoverBaseline(
        repoRoot: Path,
        request: GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult {
        goalReviewRecoverCalls++
        goalReviewRecoverRequests += request
        return goalReviewRecoveredBaseline?.let {
          GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.OK, baseline = it)
        } ?: GoalSubtaskReviewBaselineResult(
          status = WorkflowGitOperationStatus.ERROR,
          error = "no recovered baseline configured",
        )
      }
    }
}
