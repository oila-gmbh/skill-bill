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
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
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
import skillbill.workflow.model.DecompositionExecutionModel
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
  private val workerRequestHandler = GoalRunnerWorkerRequestHandler(manifestStore, outcomeStore)

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    var state = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return unknownGoal(request.issueKey)
    val attempted = mutableListOf<Int>()
    var terminalReport: GoalRunnerRunReport? = preflightPolicyBlockedReport(state, request)
    val observability = GoalRunnerObservabilityEmitter(outcomeStore, request)
    if (terminalReport == null) {
      request.eventSink.emit(GoalRunnerRunEvent.Started(state.manifest.issueKey))
    }
    while (terminalReport == null) {
      val selection = GoalRunnerPlanner.selectNext(state.manifest)
      when (selection) {
        is GoalRunnerSelection.Done -> terminalReport = finalizeGoal(state, request, attempted)
        is GoalRunnerSelection.Blocked -> blockedSelectionIteration(state, selection, request, attempted, observability)
          .also { result ->
            state = result.state
            terminalReport = result.report
          }
        is GoalRunnerSelection.Run -> {
          val result = runSelectedSubtask(state, selection, request, attempted, observability)
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
          pendingCount = terminalReport.subtasksPending,
          blockedCount = terminalReport.subtasksBlocked,
          pullRequestStatus = terminalReport.pullRequestStatus,
          pullRequestUrl = terminalReport.pullRequestUrl,
        ),
      )
    }
    return terminalReport
  }

  private fun blockedSelectionIteration(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Blocked,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    observability: GoalRunnerObservabilityEmitter,
  ): GoalRunnerIterationResult {
    val saved = manifestStore.save(
      state.copy(manifest = state.manifest.withBlockedSelection(selection.subtask.id, selection.reason)),
      request.dbPathOverride,
    )
    selection.subtask.workflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
      observability.record(
        subject = GoalRunnerObservabilitySubject(workflowId, saved.manifest.issueKey, selection.subtask.id),
        signal = GoalRunnerObservabilitySignal(
          workflowPhase = selection.subtask.lastResumableStep.orEmpty().ifBlank { "preplan" },
          livenessClass = "block",
          activitySummary = selection.reason,
        ),
      )
    }
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = selection.subtask.id,
        reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
        blockedReason = selection.reason,
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report = stopped(
        issueKey = saved.manifest.issueKey,
        attempted = attempted,
        subtaskId = selection.subtask.id,
        reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
        blockedReason = selection.reason,
        workflowId = selection.subtask.workflowId,
        lastResumableStep = selection.subtask.lastResumableStep.orEmpty().ifBlank { "preplan" },
      ),
    )
  }

  private fun runSelectedSubtask(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
    observability: GoalRunnerObservabilityEmitter,
  ): GoalRunnerIterationResult {
    val subtaskId = selection.decision.subtask.id
    goalBranchSetupFailure(state, selection, request)?.let { failure ->
      return failure
    }
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
    val launchReconciliation = reconcileLaunchOutcome(attemptedState, launchOutcome, subtaskId, request)
    val workerRequestResult = workerRequestHandler.handle(
      state = launchReconciliation.refreshed,
      launchOutcome = launchReconciliation.launchOutcome,
      subtaskId = subtaskId,
      request = request,
    )
    val refreshed = workerRequestResult.state
    val reconciled = launchReconciliation.reconciled
    refreshed.manifest.workflowIdFor(subtaskId)?.let { workflowId ->
      observability.recordLaunchLifecycle(
        subject = GoalRunnerObservabilitySubject(workflowId, refreshed.manifest.issueKey, subtaskId),
        action = selection.decision.action.name.lowercase(),
        progress = safeProgress(workflowId, request),
        launchOutcome = launchReconciliation.launchOutcome,
      )
    }
    return workerRequestResult.operatorConfirmationStop?.let { stop ->
      stoppedIteration(refreshed, subtaskId, stop, request, attempted, observability)
    } ?: when (reconciled) {
      is GoalRunnerReconciledOutcome.Complete -> completedIteration(
        refreshed,
        subtaskId,
        reconciled,
        request,
        observability,
      )
      is GoalRunnerReconciledOutcome.Stop -> stoppedIteration(
        refreshed,
        subtaskId,
        reconciled,
        request,
        attempted,
        observability,
      )
    }
  }

  private fun goalBranchSetupFailure(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult? {
    val subtaskId = selection.decision.subtask.id
    val branchPlan = state.manifest.branchPlanFor(subtaskId)
    if (branchPlan.branch.isBlank()) {
      return null
    }
    val checkout = gitOperations.checkoutBranch(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
    val setupError = if (!checkout.ok) {
      checkout.error
    } else if (branchPlan.validateBase) {
      gitOperations.validateBranchBase(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
        .takeUnless { it.ok }
        ?.error
        .orEmpty()
    } else {
      ""
    }
    return setupError.takeIf(String::isNotBlank)?.let { error ->
      blockedBranchSetupIteration(state, subtaskId, error, request)
    }
  }

  private fun blockedBranchSetupIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reason: String,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val blocked = state.manifest.withBranchSetupBlockedSubtask(subtaskId, reason)
    val saved = manifestStore.save(state.copy(manifest = blocked), request.dbPathOverride)
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED.name.lowercase(),
        blockedReason = reason,
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report = stopped(
        issueKey = saved.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = null,
        lastResumableStep = "create_branch",
      ),
    )
  }

  private fun reconcileLaunchOutcome(
    attemptedState: GoalRunnerManifestState,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val refreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: attemptedState
    val launchFacts = launchOutcome.toGoalRunnerLaunchFacts()
    val reconciled = GoalRunnerOutcomeReconciler.reconcile(
      subtaskId = subtaskId,
      launchFacts = launchFacts,
      storedOutcome = storedOutcome(refreshed, subtaskId, request),
    )
    return if (shouldRecheckTerminalOutcome(reconciled, launchFacts)) {
      recheckTerminalOutcome(attemptedState, refreshed, launchOutcome, launchFacts, subtaskId, request)
    } else {
      GoalRunnerLaunchReconciliation(refreshed = refreshed, reconciled = reconciled, launchOutcome = launchOutcome)
    }
  }

  private fun recheckTerminalOutcome(
    attemptedState: GoalRunnerManifestState,
    refreshed: GoalRunnerManifestState,
    launchOutcome: AgentRunLaunchOutcome,
    launchFacts: GoalRunnerLaunchFacts,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val lateOutcome = waitForLateTerminalOutcome(refreshed, subtaskId, request)
    return if (lateOutcome != null) {
      GoalRunnerLaunchReconciliation(
        refreshed = refreshed,
        reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId, launchFacts, lateOutcome),
        launchOutcome = launchOutcome,
      )
    } else {
      retryLaunchOutcome(attemptedState, refreshed, subtaskId, request)
    }
  }

  private fun retryLaunchOutcome(
    attemptedState: GoalRunnerManifestState,
    refreshed: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val retryLaunchOutcome = subtaskLauncher.launch(
      subtaskLaunchRequest(attemptedState.manifest.issueKey, subtaskId, request),
    )
    val retryRefreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: refreshed
    val retryLaunchFacts = retryLaunchOutcome.toGoalRunnerLaunchFacts()
    return GoalRunnerLaunchReconciliation(
      refreshed = retryRefreshed,
      reconciled = GoalRunnerOutcomeReconciler.reconcile(
        subtaskId = subtaskId,
        launchFacts = retryLaunchFacts,
        storedOutcome = storedOutcome(retryRefreshed, subtaskId, request),
      ),
      launchOutcome = retryLaunchOutcome,
    )
  }

  private fun shouldRecheckTerminalOutcome(
    reconciled: GoalRunnerReconciledOutcome,
    launchFacts: GoalRunnerLaunchFacts,
  ): Boolean = reconciled is GoalRunnerReconciledOutcome.Stop &&
    reconciled.reason == GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME &&
    shouldRetryNoTerminalOutcome(launchFacts)

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

  private fun waitForLateTerminalOutcome(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerStoredOutcome? {
    var candidate: GoalRunnerStoredOutcome? = null
    var attempts = 0
    while (candidate == null && attempts < NO_TERMINAL_OUTCOME_RECHECK_ATTEMPTS) {
      attempts += 1
      try {
        Thread.sleep(NO_TERMINAL_OUTCOME_RECHECK_DELAY_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        attempts = NO_TERMINAL_OUTCOME_RECHECK_ATTEMPTS
      }
      if (!Thread.currentThread().isInterrupted) {
        val refreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
          ?: state
        candidate = storedOutcome(refreshed, subtaskId, request)
      }
    }
    return candidate
  }

  private fun stoppedIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Stop,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    observability: GoalRunnerObservabilityEmitter,
  ): GoalRunnerIterationResult {
    val knownWorkflowId = reconciled.workflowId
      ?: state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)
    val stoppedOutcome = markChildWorkflowBlockedIfNeeded(reconciled, knownWorkflowId, request)
    val blocked = state.manifest.withStoppedSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
    val saved = manifestStore.save(state.copy(manifest = blocked), request.dbPathOverride)
    knownWorkflowId?.let { workflowId ->
      observability.record(
        subject = GoalRunnerObservabilitySubject(workflowId, saved.manifest.issueKey, subtaskId),
        signal = GoalRunnerObservabilitySignal(
          workflowPhase = stoppedOutcome.lastResumableStep,
          livenessClass = if (stoppedOutcome.reason == GoalRunnerStopReason.FAILED) "failure" else "block",
          activitySummary = stoppedOutcome.blockedReason,
        ),
      )
    }
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
          progress = knownWorkflowId?.let { workflowId -> safeProgress(workflowId, request) },
          liveness = stoppedOutcome.liveness,
        ),
        workflowId = knownWorkflowId,
        lastResumableStep = stoppedOutcome.lastResumableStep,
      ),
    )
  }

  private fun markChildWorkflowBlockedIfNeeded(
    reconciled: GoalRunnerReconciledOutcome.Stop,
    knownWorkflowId: String?,
    request: GoalRunnerRunRequest,
  ): GoalRunnerReconciledOutcome.Stop {
    if (knownWorkflowId == null || reconciled.reason !in CHILD_WORKFLOW_BLOCK_REASONS) {
      return reconciled
    }
    val progress = safeProgress(knownWorkflowId, request)
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
    return blockedStepId?.takeIf(String::isNotBlank)?.let { stepId ->
      reconciled.copy(lastResumableStep = stepId)
    } ?: reconciled
  }

  private fun safeProgress(workflowId: String, request: GoalRunnerRunRequest): GoalRunnerWorkflowProgress? =
    runCatching { outcomeStore.progress(workflowId, request.dbPathOverride) }.getOrNull()

  private fun completedIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Complete,
    request: GoalRunnerRunRequest,
    observability: GoalRunnerObservabilityEmitter,
  ): GoalRunnerIterationResult {
    val completed = manifestStore.save(
      state.copy(manifest = state.manifest.withCompletedSubtask(subtaskId, reconciled)),
      request.dbPathOverride,
    )
    request.eventSink.emit(GoalRunnerRunEvent.SubtaskCompleted(completed.manifest.issueKey, subtaskId))
    observability.record(
      subject = GoalRunnerObservabilitySubject(reconciled.workflowId, completed.manifest.issueKey, subtaskId),
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = reconciled.lastResumableStep,
        livenessClass = "completion",
        activitySummary = "Subtask $subtaskId completed with commit ${reconciled.commitSha}.",
      ),
    )
    return GoalRunnerIterationResult(state = completed)
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
      is GoalPullRequestResult.Opened ->
        completed(finalState.manifest, attempted, pullRequestUrl = result.url, pullRequestStatus = "opened")
      is GoalPullRequestResult.Existing ->
        completed(finalState.manifest, attempted, pullRequestUrl = result.url, pullRequestStatus = "existing")
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

  private fun preflightPolicyBlockedReport(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerRunReport.Stopped? {
    val violation = protectedBranchViolation(state.manifest)
    val selection = GoalRunnerPlanner.selectNext(state.manifest)
    return if (violation == null || selection is GoalRunnerSelection.Done) {
      null
    } else {
      blockedByPreflightPolicy(state, request, violation, selection)
    }
  }

  private fun blockedByPreflightPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    violation: String,
    selection: GoalRunnerSelection,
  ): GoalRunnerRunReport.Stopped {
    val subtaskId = when (selection) {
      is GoalRunnerSelection.Run -> selection.decision.subtask.id
      is GoalRunnerSelection.Blocked -> selection.subtask.id
      is GoalRunnerSelection.Done -> 0
    }
    val blockedManifest = if (subtaskId > 0) {
      state.manifest.withBlockedSelection(subtaskId, violation)
    } else {
      state.manifest.copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "blocked"),
      )
    }
    val saved = manifestStore.save(state.copy(manifest = blockedManifest), request.dbPathOverride)
    if (subtaskId > 0) {
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskStopped(
          issueKey = saved.manifest.issueKey,
          subtaskId = subtaskId,
          reason = GoalRunnerStopReason.POLICY_BLOCKED.name.lowercase(),
          blockedReason = violation,
        ),
      )
    }
    return stopped(
      issueKey = saved.manifest.issueKey,
      attempted = emptyList(),
      subtaskId = subtaskId,
      reason = GoalRunnerStopReason.POLICY_BLOCKED,
      blockedReason = violation,
      workflowId = null,
      lastResumableStep = "create_branch",
    )
  }

  private fun protectedBranchViolation(manifest: DecompositionManifest): String? =
    if (manifest.executionModel == DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK) {
      protectedBranchViolationMessage(manifest)
    } else {
      null
    }

  private fun protectedBranchViolationMessage(manifest: DecompositionManifest): String? {
    val selection = GoalRunnerPlanner.selectNext(manifest) as? GoalRunnerSelection.Run
    val selectedBranch = selection
      ?.decision
      ?.subtask
      ?.id
      ?.let { subtaskId -> manifest.branchPlanFor(subtaskId).branch }
      ?.takeIf(String::isNotBlank)
      ?: manifest.featureBranch
    val protectedBranch = protectedBranchName(selectedBranch)
      ?: return null
    return "Goal runner policy blocked execution because same-branch mode resolved to protected branch " +
      "'$protectedBranch'. Set decomposition feature/subtask branches to a non-protected branch " +
      "(for example `feat/${manifest.issueKey}-${manifest.featureName}`) before resuming."
  }

  private fun completed(
    manifest: DecompositionManifest,
    attempted: List<Int>,
    pullRequestUrl: String?,
    pullRequestStatus: String,
  ): GoalRunnerRunReport.Completed = GoalRunnerRunReport.Completed(
    issueKey = manifest.issueKey,
    attemptedSubtasks = attempted,
    pullRequestUrl = pullRequestUrl,
    pullRequestStatus = pullRequestStatus,
    subtasksCompleted = manifest.subtasks.count { it.status == "complete" || it.status == "skipped" },
    subtasksPending = manifest.subtasks.count { it.status !in setOf("complete", "skipped", "blocked") },
    subtasksBlocked = manifest.subtasks.count { it.status == "blocked" },
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
private const val MAX_NO_TERMINAL_OUTCOME_RETRY_ATTEMPTS = 1
private const val NO_TERMINAL_OUTCOME_RECHECK_ATTEMPTS = 2
private const val NO_TERMINAL_OUTCOME_RECHECK_DELAY_MILLIS = 200L
private val PROTECTED_GOAL_BRANCHES: Set<String> = setOf("main", "master", "trunk")
private val CHILD_WORKFLOW_BLOCK_REASONS: Set<GoalRunnerStopReason> = setOf(
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
)

private fun shouldRetryNoTerminalOutcome(launchFacts: GoalRunnerLaunchFacts): Boolean = !launchFacts.timedOut &&
  !launchFacts.interrupted &&
  !launchFacts.spawnFailed &&
  launchFacts.exitStatus == 0 &&
  MAX_NO_TERMINAL_OUTCOME_RETRY_ATTEMPTS > 0

private fun protectedBranchName(branch: String?): String? = branch
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?.takeIf { normalized -> normalized.lowercase() in PROTECTED_GOAL_BRANCHES }

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
    GoalRunnerStopReason.INTERRUPTED -> "killed_by_parent_interrupt"
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
      ?.let { workflowId -> runCatching { outcomeStore.progress(workflowId, request.dbPathOverride) }.getOrNull() }
    return GoalRunnerProgressState(subtask, childProgress)
  }
}

private fun skillbill.ports.agentrun.model.AgentRunLaunchOutcome.toGoalRunnerLaunchFacts(): GoalRunnerLaunchFacts =
  when (this) {
    is AgentRunLaunchFacts -> GoalRunnerLaunchFacts(
      timedOut = timedOut,
      interrupted = interrupted,
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

private fun DecompositionManifest.withBranchSetupBlockedSubtask(
  subtaskId: Int,
  reason: String,
): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        blockedReason = reason,
        lastResumableStep = "create_branch",
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

private fun DecompositionManifest.branchPlanFor(subtaskId: Int): GoalRunnerBranchPlan = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
    GoalRunnerBranchPlan(branch = featureBranch.orEmpty(), baseBranch = baseBranch, validateBase = false)
  DecompositionExecutionModel.STACKED_BRANCHES -> {
    val stackBranch = stackBranches.first { it.subtaskId == subtaskId }
    GoalRunnerBranchPlan(branch = stackBranch.branch, baseBranch = stackBranch.baseBranch, validateBase = true)
  }
}

private data class GoalRunnerBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)

private data class GoalRunnerLaunchReconciliation(
  val refreshed: GoalRunnerManifestState,
  val reconciled: GoalRunnerReconciledOutcome,
  val launchOutcome: AgentRunLaunchOutcome,
)

private data class GoalRunnerIterationResult(
  val state: GoalRunnerManifestState,
  val report: GoalRunnerRunReport? = null,
)
