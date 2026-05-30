@file:Suppress("LongParameterList", "TooManyFunctions")

package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.GoalRunnerOutcomeReconciler
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStopReport
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

@Inject
class GoalRunner(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
) {
  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    var state = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return unknownGoal(request.issueKey)
    val attempted = mutableListOf<Int>()
    var terminalReport: GoalRunnerRunReport? = null
    request.eventSink.emit(GoalRunnerRunEvent.Started(state.manifest.issueKey))
    while (terminalReport == null) {
      val selection = GoalRunnerPlanner.selectNext(state.manifest)
      when (selection) {
        is GoalRunnerSelection.Done -> terminalReport = finalizeGoal(state, request, attempted)
        is GoalRunnerSelection.Blocked -> {
          state = manifestStore.save(
            state.copy(manifest = state.manifest.withBlockedSelection(selection.subtask.id, selection.reason)),
            request.dbPathOverride,
          )
          terminalReport = stopped(
            issueKey = state.manifest.issueKey,
            attempted = attempted,
            subtaskId = selection.subtask.id,
            reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
            blockedReason = selection.reason,
            workflowId = selection.subtask.workflowId,
            lastResumableStep = selection.subtask.lastResumableStep.orEmpty().ifBlank { "preplan" },
          )
          request.eventSink.emit(
            GoalRunnerRunEvent.SubtaskStopped(
              issueKey = state.manifest.issueKey,
              subtaskId = selection.subtask.id,
              reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
              blockedReason = selection.reason,
            ),
          )
        }
        is GoalRunnerSelection.Run -> {
          val result = runSelectedSubtask(state, selection, request, attempted)
          state = result.state
          terminalReport = result.report
        }
      }
    }
    if (terminalReport is GoalRunnerRunReport.Completed) {
      request.eventSink.emit(
        GoalRunnerRunEvent.Completed(
          issueKey = terminalReport.issueKey,
          completedCount = terminalReport.subtasksCompleted,
          pullRequestUrl = terminalReport.pullRequestUrl,
        ),
      )
    }
    return terminalReport
  }

  private fun runSelectedSubtask(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
  ): GoalRunnerIterationResult {
    val subtaskId = selection.decision.subtask.id
    val attemptedState = manifestStore.save(
      state.copy(manifest = state.manifest.withAttemptedSubtask(subtaskId)),
      request.dbPathOverride,
    )
    attempted += subtaskId
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStarted(
        issueKey = attemptedState.manifest.issueKey,
        subtaskId = subtaskId,
        action = selection.decision.action.name.lowercase(),
      ),
    )
    val launchOutcome = subtaskLauncher.launch(
      subtaskLaunchRequest(attemptedState.manifest.issueKey, subtaskId, request),
    )
    val refreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: attemptedState
    val reconciled = GoalRunnerOutcomeReconciler.reconcile(
      subtaskId = subtaskId,
      launchFacts = launchOutcome.toGoalRunnerLaunchFacts(),
      storedOutcome = storedOutcome(refreshed, subtaskId, request),
    )
    return when (reconciled) {
      is GoalRunnerReconciledOutcome.Complete -> {
        val completed = manifestStore.save(
          refreshed.copy(manifest = refreshed.manifest.withCompletedSubtask(subtaskId, reconciled)),
          request.dbPathOverride,
        )
        request.eventSink.emit(GoalRunnerRunEvent.SubtaskCompleted(completed.manifest.issueKey, subtaskId))
        GoalRunnerIterationResult(state = completed)
      }
      is GoalRunnerReconciledOutcome.Stop -> stoppedIteration(refreshed, subtaskId, reconciled, request, attempted)
    }
  }

  private fun subtaskLaunchRequest(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerSubtaskLaunchRequest = GoalRunnerSubtaskLaunchRequest(
    invokedAgentId = request.invokedAgentId,
    configuredAgentOverrideId = request.configuredAgentOverrideId,
    skillRunRequest = SkillRunRequest(
      issueKey = issueKey,
      repoRoot = request.repoRoot,
      subtaskId = subtaskId,
      dbPathOverride = request.dbPathOverride,
      timeout = request.timeout,
      progressIdleTimeout = request.progressIdleTimeout,
      progressProbe = progressProbe(manifestStore, outcomeStore, issueKey, subtaskId, request),
      outputSink = request.outputSink,
    ),
  )

  private fun storedOutcome(state: GoalRunnerManifestState, subtaskId: Int, request: GoalRunnerRunRequest) =
    state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId
      ?.takeIf(String::isNotBlank)
      ?.let { workflowId ->
        outcomeStore.terminalOutcome(
          workflowId = workflowId,
          issueKey = state.manifest.issueKey,
          subtaskId = subtaskId,
          dbPathOverride = request.dbPathOverride,
        )
      }

  private fun stoppedIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Stop,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerIterationResult {
    val knownWorkflowId = reconciled.workflowId
      ?: state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)
    val stoppedOutcome = if (
      reconciled.reason in setOf(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, GoalRunnerStopReason.TIMEOUT) &&
      knownWorkflowId != null
    ) {
      val progress = outcomeStore.progress(knownWorkflowId, request.dbPathOverride)
      val blockedStepId = outcomeStore.markBlocked(
        workflowId = knownWorkflowId,
        blockedReason = reconciled.blockedReason.withStopDiagnostics(knownWorkflowId, progress, reconciled.liveness),
        lastResumableStep = reconciled.lastResumableStep,
        supervisionEvent = supervisionEvent(
          reason = reconciled.reason,
          knownWorkflowId = knownWorkflowId,
          progress = progress,
          liveness = reconciled.liveness,
        ),
        dbPathOverride = request.dbPathOverride,
      )
      blockedStepId?.takeIf(String::isNotBlank)?.let { stepId ->
        reconciled.copy(lastResumableStep = stepId)
      } ?: reconciled
    } else {
      reconciled
    }
    val blocked = state.manifest.withStoppedSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
    val saved = manifestStore.save(state.copy(manifest = blocked), request.dbPathOverride)
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = stoppedOutcome.reason.name.lowercase(),
        blockedReason = stoppedOutcome.blockedReason,
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report = stopped(
        issueKey = saved.manifest.issueKey,
        attempted = attempted,
        subtaskId = subtaskId,
        reason = stoppedOutcome.reason,
        blockedReason = stoppedOutcome.blockedReason.withStopDiagnostics(
          knownWorkflowId = knownWorkflowId,
          progress = knownWorkflowId?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) },
          liveness = stoppedOutcome.liveness,
        ),
        workflowId = knownWorkflowId,
        lastResumableStep = stoppedOutcome.lastResumableStep,
      ),
    )
  }

  private fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
  ): GoalRunnerRunReport {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      dbPathOverride = request.dbPathOverride,
    )
    val finalState = manifestStore.save(state, request.dbPathOverride)
    finalizationError(finalState.manifest, request.repoRoot)?.let { reason ->
      return stopped(
        issueKey = finalState.manifest.issueKey,
        attempted = attempted,
        subtaskId = finalState.manifest.subtasks.lastOrNull()?.id ?: 0,
        reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
        blockedReason = reason,
        workflowId = null,
        lastResumableStep = "commit_push",
      )
    }
    val result = pullRequestPort.open(finalState.manifest.toPullRequestRequest(request.repoRoot))
    return when (result) {
      is GoalPullRequestResult.Opened -> completed(finalState.manifest, attempted, result.url)
      is GoalPullRequestResult.Existing -> completed(finalState.manifest, attempted, result.url)
      is GoalPullRequestResult.Failed -> stopped(
        issueKey = finalState.manifest.issueKey,
        attempted = attempted,
        subtaskId = finalState.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 }
          ?: finalState.manifest.subtasks.last().id,
        reason = GoalRunnerStopReason.PULL_REQUEST_FAILED,
        blockedReason = result.reason,
        workflowId = null,
        lastResumableStep = "pr_description",
      )
    }
  }

  private fun finalizationError(manifest: DecompositionManifest, repoRoot: java.nio.file.Path): String? {
    val worktreeStatus = gitOperations.worktreeStatus(repoRoot)
    if (!worktreeStatus.ok) {
      return "Goal finalization could not verify worktree cleanliness: ${worktreeStatus.error}"
    }
    val dirtyPaths = parseGitPorcelainPaths(worktreeStatus.value.orEmpty())
    val manifestPath = manifest.parentSpecPath.substringBeforeLast("/") + "/decomposition-manifest.yaml"
    return if (dirtyPaths.any { path -> path == manifestPath }) {
      "Goal finalization detected an uncommitted decomposition projection delta at '$manifestPath'. " +
        "Commit/push the projection or resolve the write before opening the final PR."
    } else {
      null
    }
  }

  private fun completed(
    manifest: DecompositionManifest,
    attempted: List<Int>,
    pullRequestUrl: String?,
  ): GoalRunnerRunReport.Completed = GoalRunnerRunReport.Completed(
    issueKey = manifest.issueKey,
    attemptedSubtasks = attempted,
    pullRequestUrl = pullRequestUrl,
    subtasksCompleted = manifest.subtasks.count { it.status == "complete" || it.status == "skipped" },
  )

  private fun unknownGoal(issueKey: String): GoalRunnerRunReport.Stopped = stopped(
    issueKey = issueKey,
    attempted = emptyList(),
    subtaskId = 0,
    reason = GoalRunnerStopReason.BLOCKED,
    blockedReason = "No decomposed parent workflow was found for $issueKey.",
    workflowId = null,
    lastResumableStep = "preplan",
  )

  private fun stopped(
    issueKey: String,
    attempted: List<Int>,
    subtaskId: Int,
    reason: GoalRunnerStopReason,
    blockedReason: String,
    workflowId: String?,
    lastResumableStep: String,
  ): GoalRunnerRunReport.Stopped = GoalRunnerRunReport.Stopped(
    issueKey = issueKey,
    attemptedSubtasks = attempted,
    stop = GoalRunnerStopReport(
      issueKey = issueKey,
      subtaskId = subtaskId,
      reason = reason,
      blockedReason = blockedReason,
      workflowId = workflowId,
      lastResumableStep = lastResumableStep,
    ),
  )
}

private const val GIT_PORCELAIN_MIN_LENGTH = 4
private const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3

private fun parseGitPorcelainPaths(output: String): List<String> = output
  .lineSequence()
  .map(String::trimEnd)
  .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
  .map { line -> line.substring(GIT_PORCELAIN_STATUS_PREFIX_LENGTH).substringAfterLast(" -> ").trim() }
  .filter(String::isNotBlank)
  .toList()

private fun String.withStopDiagnostics(
  knownWorkflowId: String?,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): String {
  val details = listOfNotNull(
    knownWorkflowId?.let { workflowId -> "workflow_id=$workflowId" },
    progress?.currentStepId?.takeIf(String::isNotBlank)?.let { step -> "current_step=$step" },
    progress?.latestLivenessSignal?.takeIf(String::isNotBlank)?.let { signal -> "latest_liveness=$signal" },
    progress?.lastSnapshotUpdatedAt?.takeIf(String::isNotBlank)?.let { at -> "last_snapshot_at=$at" },
    liveness?.lastFileActivityAt?.takeIf(String::isNotBlank)?.let { at -> "last_file_activity_at=$at" },
    liveness?.lastOutputAt?.takeIf(String::isNotBlank)?.let { at -> "last_output_at=$at" },
  ).joinToString(", ")
  return if (details.isBlank()) this else "$this [$details]"
}

private fun supervisionEvent(
  reason: GoalRunnerStopReason,
  knownWorkflowId: String,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): GoalRunnerSupervisionEvent = GoalRunnerSupervisionEvent(
  phase = "goal_runner_supervision",
  reason = reason.name.lowercase(),
  continuationMode = when (reason) {
    GoalRunnerStopReason.TIMEOUT -> "killed_unresponsive_child"
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "continue_inline"
    else -> "none"
  },
  processState = liveness?.processState.orEmpty().ifBlank { "unknown" },
  workflowId = knownWorkflowId,
  stepId = progress?.currentStepId ?: liveness?.workflowStep,
  lastDurableProgress = progress?.latestLivenessSignal ?: liveness?.lastDurableProgressLabel,
  lastWorkflowSnapshotAt = progress?.lastSnapshotUpdatedAt ?: liveness?.lastWorkflowSnapshotAt,
  lastFileActivityAt = liveness?.lastFileActivityAt,
  lastOutputAt = liveness?.lastOutputAt,
)

private fun skillbill.workflow.model.DecompositionSubtask.progressToken(): String = listOf(
  status,
  workflowId.orEmpty(),
  branch.orEmpty(),
  commitSha.orEmpty(),
  blockedReason.orEmpty(),
  lastResumableStep.orEmpty(),
).joinToString("|")

private data class GoalRunnerProgressState(
  val subtask: DecompositionSubtask,
  val childProgress: GoalRunnerWorkflowProgress?,
)

private fun progressProbe(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  issueKey: String,
  subtaskId: Int,
  request: GoalRunnerRunRequest,
): AgentRunProgressProbe = GoalRunnerWorkflowProgressProbe(
  manifestStore = manifestStore,
  outcomeStore = outcomeStore,
  issueKey = issueKey,
  subtaskId = subtaskId,
  request = request,
)

private class GoalRunnerWorkflowProgressProbe(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val issueKey: String,
  private val subtaskId: Int,
  private val request: GoalRunnerRunRequest,
) : AgentRunProgressProbe {
  override fun progressToken(): String? = progressState()
    ?.let { progress ->
      listOfNotNull(progress.subtask.progressToken(), progress.childProgress?.progressToken)
    }
    ?.joinToString("\n")
    ?.takeIf(String::isNotBlank)

  override fun progressLabel(): String? = progressState()?.let { progress ->
    progress.childProgress?.let { child ->
      listOfNotNull(
        "subtask $subtaskId",
        "workflow ${child.workflowId}",
        "step ${child.currentStepId}",
        child.latestLivenessSignal,
      ).joinToString(" ")
    } ?: "subtask $subtaskId manifest updated"
  }

  private fun progressState(): GoalRunnerProgressState? {
    val subtask = manifestStore.loadByIssueKey(issueKey, request.dbPathOverride, request.repoRoot)
      ?.manifest
      ?.subtasks
      ?.firstOrNull { subtask -> subtask.id == subtaskId }
      ?: return null
    val childProgress = subtask.workflowId
      ?.takeIf(String::isNotBlank)
      ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) }
    return GoalRunnerProgressState(subtask, childProgress)
  }
}

private fun skillbill.ports.agentrun.model.AgentRunLaunchOutcome.toGoalRunnerLaunchFacts(): GoalRunnerLaunchFacts =
  when (this) {
    is AgentRunLaunchFacts -> GoalRunnerLaunchFacts(
      timedOut = timedOut,
      spawnFailed = spawnFailed,
      exitStatus = exitStatus,
      liveness = liveness?.let { snapshot ->
        GoalRunnerLivenessSnapshot(
          phase = snapshot.phase,
          reason = snapshot.reason,
          processState = snapshot.processState,
          workflowId = snapshot.workflowId,
          workflowStep = snapshot.workflowStep,
          lastDurableProgressAt = snapshot.lastDurableProgressAt,
          lastDurableProgressLabel = snapshot.lastDurableProgressLabel,
          lastWorkflowSnapshotAt = snapshot.lastWorkflowSnapshotAt,
          lastFileActivityAt = snapshot.lastFileActivityAt,
          lastFileActivityLabel = snapshot.lastFileActivityLabel,
          lastOutputAt = snapshot.lastOutputAt,
        )
      },
    )
    is UnsupportedAgentRunLaunch -> GoalRunnerLaunchFacts(spawnFailed = true)
  }

private fun DecompositionManifest.withAttemptedSubtask(subtaskId: Int): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId && subtask.status == "blocked") {
      subtask.copy(status = "in_progress", blockedReason = null)
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.withCompletedSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Complete,
): DecompositionManifest {
  val updated = copy(
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"),
    subtasks = subtasks.map { subtask ->
      if (subtask.id == subtaskId) {
        subtask.copy(
          status = "complete",
          workflowId = outcome.workflowId,
          commitSha = outcome.commitSha,
          blockedReason = null,
          lastResumableStep = outcome.lastResumableStep,
        )
      } else {
        subtask
      }
    },
  )
  return if (updated.subtasks.all { it.status == "complete" || it.status == "skipped" }) {
    updated.copy(status = "complete")
  } else {
    updated.copy(status = "in_progress")
  }
}

private fun DecompositionManifest.withStoppedSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: String? = outcome.workflowId,
): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        workflowId = knownWorkflowId ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = outcome.blockedReason,
        lastResumableStep = outcome.lastResumableStep,
      )
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.withBlockedSelection(subtaskId: Int, reason: String): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        blockedReason = reason,
        lastResumableStep = subtask.lastResumableStep ?: "preplan",
      )
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.toPullRequestRequest(repoRoot: java.nio.file.Path): GoalPullRequestRequest {
  val title = "$issueKey: $featureName"
  val body = buildString {
    appendLine("Goal: $featureName")
    appendLine()
    appendLine("Subtasks:")
    subtasks.forEach { subtask ->
      append("- ${subtask.id}. ${subtask.name}")
      subtask.commitSha?.let { append(" ($it)") }
      appendLine()
    }
  }
  return GoalPullRequestRequest(
    repoRoot = repoRoot,
    issueKey = issueKey,
    featureName = featureName,
    baseBranch = baseBranch,
    headBranch = featureBranch.orEmpty().ifBlank { branchForFinalPullRequest() },
    title = title,
    body = body,
  )
}

private fun DecompositionManifest.branchForFinalPullRequest(): String = stackBranches.lastOrNull()?.branch.orEmpty()

private data class GoalRunnerIterationResult(
  val state: GoalRunnerManifestState,
  val report: GoalRunnerRunReport? = null,
)
