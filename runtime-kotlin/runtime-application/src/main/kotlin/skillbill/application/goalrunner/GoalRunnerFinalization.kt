package skillbill.application.goalrunner

import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.featuretask.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.pruneCompletedSubtaskCheckpointRefs
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal class GoalRunnerFinalization(deps: GoalRunnerFinalizationDeps) {
  private val manifestStore = deps.manifestStore
  internal val outcomeStore = deps.outcomeStore
  private val pullRequestPort = deps.pullRequestPort
  internal val specScratchStore = deps.specScratchStore
  internal val gitOperations = deps.gitOperations
  internal val diagnostics = deps.diagnostics
  internal val unaddressedFindingsLedgerService = deps.unaddressedFindingsLedgerService
  internal val progressReader = deps.progressReader
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
