package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.pruneCompletedSubtaskCheckpointRefs
import skillbill.application.goalrunner.model.GoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
public class GoalRunnerFinalization(
  boundaries: GoalRunnerFinalizationBoundariesPort,
  progressReader: GoalRunnerProgressReader,
) {
  private val manifestStore = boundaries.manifestStore
  val outcomeStore = boundaries.outcomeStore
  private val pullRequestPort = boundaries.pullRequestPort
  val specScratchStore = boundaries.specScratchStore
  val gitOperations = boundaries.gitOperations
  val diagnostics = boundaries.diagnostics
  val unaddressedFindingsLedgerService = boundaries.unaddressedFindingsLedgerService
  val progressReader = progressReader
  fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport {
    reconcileBeforeFinalization(state, request, ledger)
    val finalState = manifestStore.save(state, request.dbPathOverride)
    commitAllRemainingWorktree(finalState.manifest, request)?.let { reason ->
      return stopped(
        StoppedReportArgs(
          issueKey = finalState.manifest.issueKey,
          attempted = attempted,
          subtaskId = finalState.manifest.subtasks.lastOrNull()?.id ?: 0,
          reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
          blockedReason = reason,
          workflowId = null,
          lastResumableStep = "commit_push",
        ),
      )
    }
    val findingsLedger = resolveFindingsLedger(finalState.manifest.issueKey, request.dbPathOverride)
    val result = pullRequestPort.open(finalState.manifest.toPullRequestRequest(request.repoRoot))
    return when (result) {
      is GoalPullRequestResult.Opened -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(
          finalState.manifest,
          attempted,
          pullRequestUrl = result.url,
          pullRequestStatus = "opened",
          findingsLedger,
        )
      }
      is GoalPullRequestResult.Existing -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(
          finalState.manifest,
          attempted,
          pullRequestUrl = result.url,
          pullRequestStatus = "existing",
          findingsLedger,
        )
      }
      is GoalPullRequestResult.Failed -> stopped(
        StoppedReportArgs(
          issueKey = finalState.manifest.issueKey,
          attempted = attempted,
          subtaskId = finalState.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 }
            ?: finalState.manifest.subtasks.last().id,
          reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
          blockedReason = result.reason,
          workflowId = null,
          lastResumableStep = "pr_description",
        ),
      )
    }
  }

  fun deleteCompletedSubtaskSpecScratch(
    manifest: DecompositionManifest,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val specPath = manifest.subtasks.firstOrNull { it.id == subtaskId }?.specPath?.takeIf(String::isNotBlank)
      ?: return
    val resolved = resolvedParentSpecPath(request.repoRoot, Path.of(specPath))
    runCatching { specScratchStore.deleteFileIfExists(resolved) }
      .onFailure { error ->
        diagnostics.warning(
          "Goal linear-mode subtask spec scratch deletion at '$resolved' failed; the completed " +
            "subtask is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }

  fun pruneCompletedCheckpointRefs(
    completed: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Complete,
    request: GoalRunnerRunRequest,
    observability: GoalRunnerObservabilityEmitter,
  ) {
    pruneCompletedSubtaskCheckpointRefs(
      gitOperations = gitOperations,
      repoRoot = request.repoRoot,
      request = FeatureTaskRuntimeCheckpointRefPruneRequest(
        issueKey = completed.manifest.issueKey,
        subtaskId = subtaskId.toString(),
        manifestCommitSha = reconciled.commitSha,
        featureBranch = completed.manifest.featureBranch,
      ),
      record = { message ->
        observability.record(
          GoalRunnerObservabilitySubject(reconciled.workflowId, completed.manifest.issueKey, subtaskId),
          GoalRunnerObservabilitySignal(
            workflowPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
            livenessClass = "degradation",
            activitySummary = message,
          ),
        )
      },
    )
  }
}

fun GoalRunnerFinalization.reconcileBeforeFinalization(
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

fun GoalRunnerFinalization.commitAllRemainingWorktree(
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

fun GoalRunnerFinalization.commitAndPushDirtyWorktree(
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

fun GoalRunnerFinalization.stageCommitAndPushAll(
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

fun GoalRunnerFinalization.verifyWorktreeCleanAfterCommitAll(request: GoalRunnerRunRequest): String? {
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

fun GoalRunnerFinalization.pushUnpushedFeatureBranchIfNeeded(featureBranch: String, repoRoot: Path): String? {
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

fun GoalRunnerFinalization.requireFeatureBranchForFinalize(featureBranch: String, repoRoot: Path): String? {
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

fun GoalRunnerFinalization.deleteGoalSpecScratchOnSuccess(
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

fun GoalRunnerFinalization.resolveFindingsLedger(
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
