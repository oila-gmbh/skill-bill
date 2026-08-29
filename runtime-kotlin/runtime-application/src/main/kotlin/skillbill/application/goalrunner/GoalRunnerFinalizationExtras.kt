package skillbill.application.goalrunner

import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

internal fun GoalRunnerFinalization.reconcileBeforeFinalization(
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  ledger: GoalRunnerLedgerRecorder,
) {
  outcomeStore.reconcileAuthoritativeOutcomes(
    issueKey = state.manifest.issueKey,
    activeWorkflowIds = emptySet(),
    gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    repoRoot = request.repoRoot,
    dbPathOverride = request.dbPathOverride,
  )
  state.manifest.subtasks
    .lastOrNull { subtask -> !subtask.workflowId.isNullOrBlank() }
    ?.let { subtask ->
      ledger.recordLedgerEntry(
        GoalRunnerLedgerContext(
          workflowId = subtask.workflowId,
          action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
          issueKey = state.manifest.issueKey,
          subtaskId = subtask.id,
          progress = subtask.workflowId?.let { progressReader.safeProgress(it, request) },
          finalReconciledResult = "goal_finalize status=${state.manifest.status}",
        ),
      )
    }
}

internal fun GoalRunnerFinalization.commitAllRemainingWorktree(
  manifest: DecompositionManifest,
  request: GoalRunnerRunRequest,
): String? {
  if (manifest.specSource == SpecSource.LINEAR) {
    deleteGoalSpecScratchOnSuccess(manifest, request)
  }
  val before = gitOperations.worktreeStatus(request.repoRoot)
  if (!before.ok) {
    return "Goal finalization could not verify worktree cleanliness: ${before.error}"
  }
  val dirtyPaths = parseGitPorcelainPaths(before.value.orEmpty())
  val implementationPaths = dirtyPaths.filterNot(::isFeatureSpecPath)
  val featureBranch = manifest.featureBranch.orEmpty().trim()
  if (implementationPaths.isEmpty()) {
    return pushUnpushedFeatureBranchIfNeeded(featureBranch, request.repoRoot)
  }
  return commitAndPushDirtyWorktree(manifest, request, featureBranch, implementationPaths)
}

internal fun GoalRunnerFinalization.commitAndPushDirtyWorktree(
  manifest: DecompositionManifest,
  request: GoalRunnerRunRequest,
  featureBranch: String,
  implementationPaths: List<String>,
): String? {
  if (manifest.executionModel == DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK) {
    val sample = implementationPaths.take(MAX_REPORTED_FINALIZE_DIRTY_PATHS).joinToString(", ")
    val suffix = if (implementationPaths.size > MAX_REPORTED_FINALIZE_DIRTY_PATHS) {
      " (+${implementationPaths.size - MAX_REPORTED_FINALIZE_DIRTY_PATHS} more)"
    } else {
      ""
    }
    return "Goal finalization in same-branch mode refuses to commit leftover implementation paths " +
      "($sample$suffix); route each through subtask commit_push finalization."
  }
  if (featureBranch.isBlank()) {
    return "Goal finalization commit-all requires a feature branch."
  }
  val branchError = requireFeatureBranchForFinalize(featureBranch, request.repoRoot)
  val commitError = branchError ?: stageCommitAndPushAll(manifest, request, featureBranch, implementationPaths)
  return commitError ?: verifyWorktreeCleanAfterCommitAll(request)
}

internal fun GoalRunnerFinalization.stageCommitAndPushAll(
  manifest: DecompositionManifest,
  request: GoalRunnerRunRequest,
  featureBranch: String,
  implementationPaths: List<String>,
): String? {
  val staged = gitOperations.stagePaths(request.repoRoot, implementationPaths)
  if (!staged.ok) {
    return "Goal finalization commit-all could not stage remaining worktree changes: ${staged.error}"
  }
  val message = "chore(${manifest.issueKey}): goal finalization commit-all on '$featureBranch'"
  val commit = gitOperations.createCommit(request.repoRoot, message)
  val createdCommit = commit.ok && commit.value.isNotBlank()
  if (!createdCommit) {
    if (!commit.ok && !commit.recordsNothingToCommit()) {
      return "Goal finalization commit-all could not commit remaining worktree changes: ${commit.error}"
    }
    return pushUnpushedFeatureBranchIfNeeded(featureBranch, request.repoRoot)
  }
  val pushed = gitOperations.pushBranch(request.repoRoot, featureBranch)
  return if (pushed.ok) {
    null
  } else {
    "Goal finalization commit-all committed remaining changes but could not push " +
      "branch '$featureBranch': ${pushed.error}"
  }
}

internal fun GoalRunnerFinalization.verifyWorktreeCleanAfterCommitAll(request: GoalRunnerRunRequest): String? {
  val after = gitOperations.worktreeStatus(request.repoRoot)
  if (!after.ok) {
    return "Goal finalization could not re-verify worktree cleanliness after commit-all: ${after.error}"
  }
  val remaining = parseGitPorcelainPaths(after.value.orEmpty()).filterNot(::isFeatureSpecPath)
  return if (remaining.isEmpty()) {
    null
  } else {
    "Goal finalization commit-all left dirty paths after commit/push: " +
      remaining.take(MAX_REPORTED_FINALIZE_DIRTY_PATHS).joinToString(", ") +
      if (remaining.size > MAX_REPORTED_FINALIZE_DIRTY_PATHS) {
        " (+${remaining.size - MAX_REPORTED_FINALIZE_DIRTY_PATHS} more)"
      } else {
        ""
      }
  }
}

internal fun GoalRunnerFinalization.pushUnpushedFeatureBranchIfNeeded(featureBranch: String, repoRoot: Path): String? {
  if (featureBranch.isBlank()) return null
  val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, featureBranch)
  if (!unpushed.ok) {
    return "Goal finalization could not determine whether '$featureBranch' has unpushed commits: " +
      unpushed.error
  }
  if (unpushed.value.trim() != "true") return null
  return requireFeatureBranchForFinalize(featureBranch, repoRoot)
    ?: gitOperations.pushBranch(repoRoot, featureBranch)
      .takeIf { !it.ok }
      ?.let { "Goal finalization found unpushed commits on '$featureBranch' but could not push: ${it.error}" }
}

internal fun GoalRunnerFinalization.requireFeatureBranchForFinalize(featureBranch: String, repoRoot: Path): String? {
  protectedBranchName(featureBranch)?.let { protected ->
    return "Goal finalization commit-all refuses protected branch '$protected'."
  }
  val current = gitOperations.currentBranch(repoRoot)
  if (!current.ok) {
    return "Goal finalization could not read the current branch: ${current.error}"
  }
  val currentBranch = current.value.trim()
  if (currentBranch != featureBranch) {
    return "Goal finalization commit-all requires checkout of feature branch '$featureBranch' " +
      "(current branch is '${currentBranch.ifBlank { "<detached/empty>" }}')."
  }
  return null
}

internal fun GoalRunnerFinalization.deleteGoalSpecScratchOnSuccess(
  manifest: DecompositionManifest,
  request: GoalRunnerRunRequest,
) {
  if (manifest.specSource != SpecSource.LINEAR) return
  val parentSpec = resolvedParentSpecPath(request.repoRoot, Path.of(manifest.parentSpecPath))
  val specDir = parentSpec.parent ?: return
  runCatching { specScratchStore.deleteDirectoryIfExists(specDir) }
    .onFailure { error ->
      diagnostics.warning(
        "Goal linear-mode spec scratch deletion at '$specDir' failed; the completed goal is " +
          "unaffected and the scratch can be cleaned up manually.",
        error,
      )
    }
}

internal fun GoalRunnerFinalization.resolveFindingsLedger(
  issueKey: String,
  dbPathOverride: String?,
): UnaddressedFindingsLedger? {
  val service = unaddressedFindingsLedgerService ?: return null
  return try {
    service.ledger(issueKey, dbPathOverride)
  } catch (_: UnaddressedFindingsLedgerAbsentError) {
    UnaddressedFindingsLedger(issueKey, emptyList())
  } catch (_: InvalidUnaddressedFindingsLedgerSchemaError) {
    null
  }
}
