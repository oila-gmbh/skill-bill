@file:Suppress("LargeClass", "LongParameterList", "TooManyFunctions")

package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.GoalRunnerOutcomeReconciler
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStopReport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAction
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.SpecScratchStore
import skillbill.ports.workflow.UnavailableSpecScratchStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import kotlin.time.Duration.Companion.milliseconds

@Inject
class GoalRunner(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val specScratchStore: SpecScratchStore = UnavailableSpecScratchStore,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val telemetry: GoalLifecycleTelemetryEmitter = GoalLifecycleTelemetryEmitter.NONE,
  private val clock: java.time.Clock = java.time.Clock.systemUTC(),
  private val timing: RuntimeTimingPort = NoopRuntimeTimingPort,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val workerRequestHandler = GoalRunnerWorkerRequestHandler(manifestStore, outcomeStore)
  private val reconciler = GoalRunnerLaunchReconciler(manifestStore, subtaskLauncher, outcomeStore, timing, diagnostics)

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    var state = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return unknownGoal(request.issueKey)
    val attempted = mutableListOf<Int>()
    val observability = GoalRunnerObservabilityEmitter(outcomeStore, request)
    val ledger = GoalRunnerLedgerRecorder(outcomeStore, request, diagnostics)
    var terminalReport: GoalRunnerRunReport? = preflightPolicyBlockedReport(state, request, ledger)
    val telemetryEmitter = if (terminalReport == null) {
      request.eventSink.emit(GoalRunnerRunEvent.Started(state.manifest.issueKey))
      GoalRunnerTelemetryEmitter(telemetry, clock, state, request.dbPathOverride).also { it.goalStarted() }
    } else {
      null
    }
    while (terminalReport == null) {
      val selection = GoalRunnerPlanner.selectNext(state.manifest)
      when (selection) {
        is GoalRunnerSelection.Done -> terminalReport = finalizeGoal(state, request, attempted, ledger)
        is GoalRunnerSelection.Blocked ->
          blockedSelectionIteration(state, selection, request, attempted, observability, ledger)
            .also { result ->
              state = result.state
              terminalReport = result.report
            }
        is GoalRunnerSelection.Run -> {
          val result = runSelectedSubtask(
            state,
            selection,
            request,
            attempted,
            observability,
            ledger,
            telemetryEmitter,
          )
          state = result.state
          terminalReport = result.report
        }
      }
      telemetryEmitter?.emitNewlyTerminalSubtasks(state.manifest, attempted)
    }
    val finalReport = terminalReport
    closeGoalTelemetrySegment(telemetryEmitter, state, finalReport, attempted)
    if (finalReport is GoalRunnerRunReport.Completed) {
      request.eventSink.emit(
        GoalRunnerRunEvent.Completed(
          issueKey = finalReport.issueKey,
          completedCount = finalReport.subtasksCompleted,
          pendingCount = finalReport.subtasksPending,
          blockedCount = finalReport.subtasksBlocked,
          pullRequestStatus = finalReport.pullRequestStatus,
          pullRequestUrl = finalReport.pullRequestUrl,
        ),
      )
    }
    return finalReport
  }

  private fun closeGoalTelemetrySegment(
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
    state: GoalRunnerManifestState,
    finalReport: GoalRunnerRunReport,
    attempted: List<Int>,
  ) {
    telemetryEmitter?.let { emitter ->
      emitter.emitNewlyTerminalSubtasks(state.manifest, attempted)
      emitter.goalFinished(state.manifest, finalReport)
    }
  }

  private fun blockedSelectionIteration(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Blocked,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
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
      ledger.recordLedgerEntry(
        GoalRunnerLedgerContext(
          workflowId = workflowId,
          action = GoalAttemptLedgerAction.POLICY_BLOCK,
          issueKey = saved.manifest.issueKey,
          subtaskId = selection.subtask.id,
          progress = safeProgress(workflowId, request),
          blockedReason = selection.reason,
          stopReason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
        ),
      )
    }
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = selection.subtask.id,
        reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
        blockedReason = selection.reason,
        currentStepId = selection.subtask.lastResumableStep?.takeIf(String::isNotBlank),
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
    ledger: GoalRunnerLedgerRecorder,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
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
    emitSubtaskStarted(attemptedState, subtaskId, selection, request, telemetryEmitter)
    val launchOutcome = subtaskLauncher.launch(
      reconciler.subtaskLaunchRequest(attemptedState.manifest.issueKey, subtaskId, request),
    )
    val launchReconciliation = reconciler.reconcileLaunchOutcome(attemptedState, launchOutcome, subtaskId, request)
    val workerRequestResult = workerRequestHandler.handle(
      state = launchReconciliation.refreshed,
      launchOutcome = launchReconciliation.launchOutcome,
      subtaskId = subtaskId,
      request = request,
    )
    val refreshed = workerRequestResult.state
    val reconciled = launchReconciliation.reconciled
    refreshed.manifest.workflowIdFor(subtaskId)?.let { workflowId ->
      recordLaunchObservabilityLedgerAndAccounting(
        LaunchRecordingContext(workflowId, refreshed, subtaskId, selection, launchReconciliation),
        safeProgress(workflowId, request),
        observability,
        ledger,
      )
    }
    return workerRequestResult.operatorConfirmationStop?.let { stop ->
      stoppedIteration(refreshed, subtaskId, stop, request, attempted, observability, ledger)
    } ?: when (reconciled) {
      is GoalRunnerReconciledOutcome.Complete -> completedIteration(
        refreshed,
        subtaskId,
        reconciled,
        request,
        observability,
        ledger,
      )
      is GoalRunnerReconciledOutcome.Stop -> stoppedIteration(
        refreshed,
        subtaskId,
        reconciled,
        request,
        attempted,
        observability,
        ledger,
        launchReconciliation.diagnostics,
      )
    }
  }

  private fun emitSubtaskStarted(
    attemptedState: GoalRunnerManifestState,
    subtaskId: Int,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
  ) {
    telemetryEmitter?.markSubtaskStarted(subtaskId)
    val currentStepId = attemptedState.manifest.subtasks
      .firstOrNull { it.id == subtaskId }
      ?.let { subtask ->
        subtask.workflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
          safeProgress(workflowId, request)?.currentStepId
        } ?: subtask.lastResumableStep?.takeIf(String::isNotBlank)
      }
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStarted(
        issueKey = attemptedState.manifest.issueKey,
        subtaskId = subtaskId,
        action = selection.decision.action.name.lowercase(),
        currentStepId = currentStepId,
      ),
    )
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
        currentStepId = "create_branch",
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

  private fun stoppedIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Stop,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    launchDiagnostics: GoalRunnerLaunchDiagnostics? = null,
  ): GoalRunnerIterationResult {
    val knownWorkflowId = state.manifest.knownWorkflowId(subtaskId, reconciled)
    val stoppedOutcome = markChildWorkflowBlockedIfNeeded(reconciled, knownWorkflowId, request)
    knownWorkflowId?.let { workflowId ->
      ledger.recordLedgerEntry(
        GoalRunnerLedgerContext(
          workflowId = workflowId,
          action = stoppedOutcome.reason.toLedgerAction(),
          issueKey = state.manifest.issueKey,
          subtaskId = subtaskId,
          progress = safeProgress(workflowId, request),
          blockedReason = stoppedOutcome.blockedReason,
          finalReconciledResult = stoppedOutcome.reason.name.lowercase(),
          stopReason = stoppedOutcome.reason.name.lowercase(),
          diagnosticClass = launchDiagnostics?.diagnosticClass ?: stoppedOutcome.reason.toDiagnosticClass(),
          recoverableJsonPresent = launchDiagnostics?.recoverableJsonPresent ?: false,
          nextSafeAction = launchDiagnostics?.nextSafeAction ?: stoppedOutcome.reason.nextSafeAction(),
        ),
      )
    }
    val blocked = state.manifest.withStoppedSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
    validationRetryIteration(blocked, stoppedOutcome, subtaskId, state, request)
      ?.let { retry -> return retry }
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
    request.emitStoppedSubtaskEvent(saved.manifest.issueKey, subtaskId, stoppedOutcome)
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

  private fun validationRetryIteration(
    blocked: DecompositionManifest,
    stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
    subtaskId: Int,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult? {
    if (!stoppedOutcome.isRecoverableValidationBlock()) {
      return null
    }
    return GoalRunnerIterationResult(
      state = manifestStore.save(
        state.copy(manifest = blocked.withValidationQualityRetrySubtask(subtaskId)),
        request.dbPathOverride,
      ),
    )
  }

  private fun GoalRunnerRunRequest.emitStoppedSubtaskEvent(
    issueKey: String,
    subtaskId: Int,
    stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  ) {
    eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = issueKey,
        subtaskId = subtaskId,
        reason = stoppedOutcome.reason.name.lowercase(),
        blockedReason = stoppedOutcome.blockedReason,
        currentStepId = stoppedOutcome.lastResumableStep.takeIf(String::isNotBlank),
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
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerIterationResult {
    val completed = manifestStore.save(
      state.copy(manifest = state.manifest.withCompletedSubtask(subtaskId, reconciled)),
      request.dbPathOverride,
    )
    // Linear mode: the subtask's spec scratch is excluded from the commit, so once its commit is
    // durable (commitSha recorded above) delete that subtask's spec file. The manifest survives — it
    // is live runtime state for the remaining subtasks and is removed only at finalize. Local mode
    // keeps the spec on disk (it was committed). Deletion is failure-isolated and idempotent.
    deleteCompletedSubtaskSpecScratch(completed.manifest, subtaskId, request)
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskCompleted(
        issueKey = completed.manifest.issueKey,
        subtaskId = subtaskId,
        currentStepId = reconciled.lastResumableStep.takeIf(String::isNotBlank),
      ),
    )
    observability.record(
      subject = GoalRunnerObservabilitySubject(reconciled.workflowId, completed.manifest.issueKey, subtaskId),
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = reconciled.lastResumableStep,
        livenessClass = "completion",
        activitySummary = "Subtask $subtaskId completed with commit ${reconciled.commitSha}.",
      ),
    )
    ledger.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = reconciled.workflowId,
        action = GoalAttemptLedgerAction.TERMINAL_DONE_CHECK,
        issueKey = completed.manifest.issueKey,
        subtaskId = subtaskId,
        progress = safeProgress(reconciled.workflowId, request),
        finalReconciledResult = "complete commit=${reconciled.commitSha}",
      ),
    )
    return GoalRunnerIterationResult(state = completed)
  }

  private fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      // SKILL-68: the command-path finalize supplies the repo root so a complete-without-SHA child
      // is healed from measured HEAD and durably backfilled before the goal completes.
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
            progress = subtask.workflowId?.let { safeProgress(it, request) },
            finalReconciledResult = "goal_finalize status=${state.manifest.status}",
          ),
        )
      }
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
      is GoalPullRequestResult.Opened -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(finalState.manifest, attempted, pullRequestUrl = result.url, pullRequestStatus = "opened")
      }
      is GoalPullRequestResult.Existing -> {
        deleteGoalSpecScratchOnSuccess(finalState.manifest, request)
        completed(finalState.manifest, attempted, pullRequestUrl = result.url, pullRequestStatus = "existing")
      }
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
    // In linear mode the manifest is never staged (it is excluded from the commit and deleted on
    // success), so an untracked/uncommitted manifest is expected, not a blocking projection delta.
    // Only local mode commits the manifest, so only local mode flags a dirty manifest here.
    val manifestPath = manifest.parentSpecPath.substringBeforeLast("/") + "/decomposition-manifest.yaml"
    val manifestProjectionDirty = manifest.specSource == SpecSource.LOCAL &&
      parseGitPorcelainPaths(worktreeStatus.value.orEmpty()).any { path -> path == manifestPath }
    return if (manifestProjectionDirty) {
      "Goal finalization detected an uncommitted decomposition projection delta at '$manifestPath'. " +
        "Commit/push the projection or resolve the write before opening the final PR."
    } else {
      null
    }
  }

  // Deletes one completed subtask's local spec file in linear mode. No-op in local mode and for a
  // missing/blank spec path; failure-isolated so a delete fault cannot falsely-fail a good subtask.
  private fun deleteCompletedSubtaskSpecScratch(
    manifest: DecompositionManifest,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val specPath = manifest.subtasks.firstOrNull { it.id == subtaskId }?.specPath?.takeIf(String::isNotBlank)
      ?: return
    val resolved = resolvedParentSpecPath(request.repoRoot, java.nio.file.Path.of(specPath))
    runCatching { specScratchStore.deleteFileIfExists(resolved) }
      .onFailure { error ->
        diagnostics.warning(
          "Goal linear-mode subtask spec scratch deletion at '$resolved' failed; the completed " +
            "subtask is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }

  // Deletes the parent spec + manifest (the whole decomposition scratch dir) after the goal reaches
  // terminal success — every subtask complete and the final PR opened/existing. The on-disk manifest
  // is no longer needed (the PR request was already built from the in-memory manifest) and the
  // individual subtask specs were already removed incrementally. Linear mode only; failure-isolated.
  private fun deleteGoalSpecScratchOnSuccess(manifest: DecompositionManifest, request: GoalRunnerRunRequest) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val parentSpec = resolvedParentSpecPath(request.repoRoot, java.nio.file.Path.of(manifest.parentSpecPath))
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

  private fun preflightPolicyBlockedReport(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport.Stopped? {
    val violation = protectedBranchViolation(state.manifest)
    val selection = GoalRunnerPlanner.selectNext(state.manifest)
    return if (violation == null || selection is GoalRunnerSelection.Done) {
      null
    } else {
      blockedByPreflightPolicy(state, request, violation, selection, ledger)
    }
  }

  private fun blockedByPreflightPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    violation: String,
    selection: GoalRunnerSelection,
    ledger: GoalRunnerLedgerRecorder,
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
    ledger.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = saved.parentWorkflowId,
        action = GoalAttemptLedgerAction.POLICY_BLOCK,
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        blockedReason = violation,
        stopReason = GoalRunnerStopReason.POLICY_BLOCKED.name.lowercase(),
      ),
    )
    if (subtaskId > 0) {
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskStopped(
          issueKey = saved.manifest.issueKey,
          subtaskId = subtaskId,
          reason = GoalRunnerStopReason.POLICY_BLOCKED.name.lowercase(),
          blockedReason = violation,
          currentStepId = "create_branch",
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

@Suppress("TooManyFunctions")
internal class GoalRunnerLaunchReconciler(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val timing: RuntimeTimingPort = NoopRuntimeTimingPort,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun subtaskLaunchRequest(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerSubtaskLaunchRequest {
    val tickReader = GoalRunnerTickProgressReader(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      issueKey = issueKey,
      subtaskId = subtaskId,
      request = request,
    )
    val progressWatermark = runCatching {
      outcomeStore.ledgerSequenceWatermarks(issueKey, request.dbPathOverride).maxProgressSequence
    }.getOrNull()
    val progressEmitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomeStore,
      request = request,
      resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId?.takeIf(String::isNotBlank) },
      watermarkSeed = progressWatermark,
      diagnostics = diagnostics,
    )
    return GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      skillRunRequest = SkillRunRequest(
        issueKey = issueKey,
        repoRoot = request.repoRoot,
        subtaskId = subtaskId,
        dbPathOverride = request.dbPathOverride,
        timeout = request.timeout,
        progressIdleTimeout = request.progressIdleTimeout,
        progressProbe = progressProbe(tickReader, subtaskId),
        declaredProgressProbe = declaredProgressProbe(tickReader),
        progressEmitter = progressEmitter,
        outputSink = request.outputSink,
        goalContinuation = goalContinuationContext(issueKey, subtaskId, request),
      ),
    )
  }

  private fun goalContinuationContext(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): SkillRunGoalContinuationContext? {
    val state = manifestStore.loadByIssueKey(issueKey, request.dbPathOverride, request.repoRoot) ?: return null
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
    val subtask = state.manifest.subtasks.firstOrNull { it.id == subtaskId }
    val specPath = subtask?.specPath?.takeIf(String::isNotBlank)
    return if (branch != null && subtask != null && specPath != null) {
      SkillRunGoalContinuationContext(
        parentIssueKey = issueKey,
        subtaskId = subtaskId,
        goalBranch = branch,
        suppressPr = true,
        specPath = specPath,
        parentWorkflowId = state.parentWorkflowId,
        lastResumableStep = subtask.lastResumableStep?.takeIf(String::isNotBlank),
        childWorkflowId = state.manifest.workflowIdFor(subtaskId),
      )
    } else {
      null
    }
  }

  fun reconcileLaunchOutcome(
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
      launchReconciliation(refreshed, reconciled, launchOutcome, subtaskId, request)
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

  private fun launchReconciliation(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val recovery = missingResultPrefixRecovery(refreshed, reconciled, launchOutcome, subtaskId, request)
    val recoveredReconciled = recovery?.storedOutcome?.let { recoveredOutcome ->
      GoalRunnerOutcomeReconciler.reconcile(
        subtaskId = subtaskId,
        launchFacts = launchOutcome.toGoalRunnerLaunchFacts(),
        storedOutcome = recoveredOutcome,
      )
    } ?: reconciled
    return GoalRunnerLaunchReconciliation(
      refreshed = refreshed,
      reconciled = recoveredReconciled,
      launchOutcome = launchOutcome,
      diagnostics = recovery?.diagnostics ?: malformedResultJsonDiagnostics(reconciled, launchOutcome),
    )
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
    val reconciled = GoalRunnerOutcomeReconciler.reconcile(
      subtaskId = subtaskId,
      launchFacts = retryLaunchFacts,
      storedOutcome = storedOutcome(retryRefreshed, subtaskId, request),
    )
    return launchReconciliation(retryRefreshed, reconciled, retryLaunchOutcome, subtaskId, request)
  }

  private fun missingResultPrefixRecovery(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerMissingResultPrefixRecovery? = missingPrefixRecoveryCandidate(
    reconciled,
    launchOutcome,
  )?.let { candidate ->
    val workflowId = refreshed.manifest.workflowIdFor(subtaskId)
      ?: candidate.workflowId
    val storedOutcome = workflowId?.let { resolvedWorkflowId ->
      outcomeStore.recoverMissingResultPrefixOutput(
        workflowId = resolvedWorkflowId,
        issueKey = request.issueKey,
        subtaskId = subtaskId,
        output = candidate.output,
        dbPathOverride = request.dbPathOverride,
      )
    }
    GoalRunnerMissingResultPrefixRecovery(
      storedOutcome = storedOutcome,
      diagnostics = missingResultPrefixDiagnostics(storedOutcome?.lastResumableStep ?: candidate.lastResumableStep),
    )
  }

  private fun shouldRecheckTerminalOutcome(
    reconciled: GoalRunnerReconciledOutcome,
    launchFacts: GoalRunnerLaunchFacts,
  ): Boolean = reconciled is GoalRunnerReconciledOutcome.Stop &&
    reconciled.reason == GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME &&
    shouldRetryNoTerminalOutcome(launchFacts)

  private fun storedOutcome(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerStoredOutcome? {
    val manifestWorkflowId = state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId
      ?.takeIf(String::isNotBlank)
    if (manifestWorkflowId != null) {
      return outcomeStore.recoverAndPersistTerminalOutcome(
        workflowId = manifestWorkflowId,
        issueKey = state.manifest.issueKey,
        subtaskId = subtaskId,
        repoRoot = request.repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
    }
    return outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      repoRoot = request.repoRoot,
      dbPathOverride = request.dbPathOverride,
    )[subtaskId]
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
      val waitResult = timing.wait(NO_TERMINAL_OUTCOME_RECHECK_DELAY_MILLIS.milliseconds)
      if (waitResult == RuntimeWaitResult.COMPLETED) {
        val refreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
          ?: state
        candidate = storedOutcome(refreshed, subtaskId, request)
      } else {
        attempts = NO_TERMINAL_OUTCOME_RECHECK_ATTEMPTS
      }
    }
    return candidate
  }
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

private fun GoalRunnerReconciledOutcome.Stop.isRecoverableValidationBlock(): Boolean =
  reason in setOf(GoalRunnerStopReason.BLOCKED, GoalRunnerStopReason.FAILED) &&
    lastResumableStep == "validate"

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

private class GoalRunnerTickProgressReader(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val issueKey: String,
  private val subtaskId: Int,
  private val request: GoalRunnerRunRequest,
  private val clockNanos: () -> Long = System::nanoTime,
) {
  private var cachedAtNanos: Long = 0
  private var cachedHasValue: Boolean = false
  private var cached: GoalRunnerProgressState? = null

  fun progressState(): GoalRunnerProgressState? {
    val now = clockNanos()
    if (cachedHasValue && now - cachedAtNanos < TICK_MEMO_WINDOW_NANOS) {
      return cached
    }
    cached = resolve()
    cachedAtNanos = now
    cachedHasValue = true
    return cached
  }

  private fun resolve(): GoalRunnerProgressState? {
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

  private companion object {
    const val SUPERVISOR_POLL_CADENCE_NANOS: Long = 250_000_000L
    const val TICK_MEMO_WINDOW_NANOS: Long = SUPERVISOR_POLL_CADENCE_NANOS - 50_000_000L
  }
}

private fun progressProbe(reader: GoalRunnerTickProgressReader, subtaskId: Int): AgentRunProgressProbe =
  GoalRunnerWorkflowProgressProbe(reader = reader, subtaskId = subtaskId)

private class GoalRunnerWorkflowProgressProbe(
  private val reader: GoalRunnerTickProgressReader,
  private val subtaskId: Int,
) : AgentRunProgressProbe {
  override fun progressToken(): String? = reader.progressState()
    ?.let { progress ->
      listOfNotNull(progress.subtask.progressToken(), progress.childProgress?.progressToken)
    }
    ?.joinToString("\n")
    ?.takeIf(String::isNotBlank)

  override fun progressLabel(): String? = reader.progressState()?.let { progress ->
    progress.childProgress?.let { child ->
      listOfNotNull(
        "subtask $subtaskId",
        "workflow ${child.workflowId}",
        "step ${child.currentStepId}",
        child.latestLivenessSignal,
      ).joinToString(" ")
    } ?: "subtask $subtaskId manifest updated"
  }
}

private fun declaredProgressProbe(
  reader: GoalRunnerTickProgressReader,
): skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe =
  skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe {
    reader.progressState()
      ?.childProgress
      ?.latestDeclaredProgressEvent
      ?.let { event ->
        skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot(
          latestEvent = event,
          processAlive = event.processAlive,
        )
      }
  }

private fun skillbill.ports.agentrun.model.AgentRunLaunchOutcome.toGoalRunnerLaunchFacts(): GoalRunnerLaunchFacts =
  when (this) {
    is AgentRunLaunchFacts -> GoalRunnerLaunchFacts(
      timedOut = timedOut,
      interrupted = interrupted,
      spawnFailed = spawnFailed,
      exitStatus = exitStatus,
      stderrTail = stderr.takeLast(GoalRunnerLaunchFacts.STDERR_TAIL_MAX_CHARS).takeIf(String::isNotBlank),
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

private fun DecompositionManifest.knownWorkflowId(subtaskId: Int, outcome: GoalRunnerReconciledOutcome.Stop): String? =
  outcome.workflowId ?: subtasks.firstOrNull { it.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)

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

private fun DecompositionManifest.withValidationQualityRetrySubtask(subtaskId: Int): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(status = "in_progress", blockedReason = null)
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

internal data class GoalRunnerLaunchReconciliation(
  val refreshed: GoalRunnerManifestState,
  val reconciled: GoalRunnerReconciledOutcome,
  val launchOutcome: AgentRunLaunchOutcome,
  val diagnostics: GoalRunnerLaunchDiagnostics? = null,
)

internal data class GoalRunnerLaunchDiagnostics(
  val diagnosticClass: String,
  val recoverableJsonPresent: Boolean,
  val nextSafeAction: String,
)

private data class GoalRunnerMissingResultPrefixRecovery(
  val storedOutcome: GoalRunnerStoredOutcome?,
  val diagnostics: GoalRunnerLaunchDiagnostics,
)

private data class GoalRunnerMissingResultPrefixCandidate(
  val output: Map<String, Any?>,
  val lastResumableStep: String?,
  val workflowId: String?,
)

private fun GoalRunnerStopReason.toLedgerAction(): GoalAttemptLedgerAction = when (this) {
  GoalRunnerStopReason.TIMEOUT -> GoalAttemptLedgerAction.TIMEOUT
  GoalRunnerStopReason.INTERRUPTED -> GoalAttemptLedgerAction.INTERRUPTION
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> GoalAttemptLedgerAction.RETRY
  else -> GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME
}

private data class LaunchRecordingContext(
  val workflowId: String,
  val refreshed: GoalRunnerManifestState,
  val subtaskId: Int,
  val selection: GoalRunnerSelection.Run,
  val launchReconciliation: GoalRunnerLaunchReconciliation,
)

private fun recordLaunchObservabilityLedgerAndAccounting(
  context: LaunchRecordingContext,
  progress: GoalRunnerWorkflowProgress?,
  observability: GoalRunnerObservabilityEmitter,
  ledger: GoalRunnerLedgerRecorder,
) {
  val workflowId = context.workflowId
  val subtaskId = context.subtaskId
  val launchOutcome = context.launchReconciliation.launchOutcome
  observability.recordLaunchLifecycle(
    subject = GoalRunnerObservabilitySubject(workflowId, context.refreshed.manifest.issueKey, subtaskId),
    action = context.selection.decision.action.name.lowercase(),
    progress = progress,
    launchOutcome = launchOutcome,
  )
  ledger.recordLedgerEntry(
    GoalRunnerLedgerContext(
      workflowId = workflowId,
      action = if (context.selection.decision.action == GoalRunnerSubtaskAction.RESUME) {
        GoalAttemptLedgerAction.RESUME
      } else {
        GoalAttemptLedgerAction.CHILD_ACTIVATION
      },
      issueKey = context.refreshed.manifest.issueKey,
      subtaskId = subtaskId,
      progress = progress,
      launchOutcome = launchOutcome,
      diagnosticClass = (launchOutcome as? AgentRunLaunchFacts)?.takeIf {
        it.spawnFailed || it.interrupted || it.timedOut || (it.exitStatus != null && it.exitStatus != 0)
      }?.let { "child_process_failed" },
      recoverableJsonPresent = null,
      nextSafeAction = "read_terminal_workflow_state",
    ),
  )
  ledger.recordAccounting(
    workflowId = workflowId,
    subtaskId = subtaskId,
    phase = progress?.currentStepId.orEmpty(),
    launchOutcome = launchOutcome,
  )
}

private fun GoalRunnerStopReason.toDiagnosticClass(): String = when (this) {
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_workflow_state"
  GoalRunnerStopReason.FAILED -> "malformed_result_json"
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
  GoalRunnerStopReason.BLOCKED,
  -> "child_process_failed"
  else -> name.lowercase()
}

private fun GoalRunnerStopReason.nextSafeAction(): String = when (this) {
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
  -> "resume_from_last_resumable_step"
  GoalRunnerStopReason.FAILED -> "inspect_child_output_then_resume"
  else -> "inspect_blocked_reason"
}

private fun missingResultPrefixDiagnostics(lastResumableStep: String?): GoalRunnerLaunchDiagnostics =
  GoalRunnerLaunchDiagnostics(
    diagnosticClass = "missing_result_prefix",
    recoverableJsonPresent = true,
    nextSafeAction = if (lastResumableStep.isNullOrBlank()) {
      "continue_inline"
    } else {
      "resume_from_last_resumable_step"
    },
  )

private fun missingPrefixRecoveryCandidate(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerMissingResultPrefixCandidate? = (reconciled as? GoalRunnerReconciledOutcome.Stop)
  ?.takeIf { stop -> stop.reason == GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME }
  ?.let { stop ->
    (launchOutcome as? AgentRunLaunchFacts)?.let { facts ->
      terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr)?.let { output ->
        GoalRunnerMissingResultPrefixCandidate(
          output = output,
          lastResumableStep = stop.lastResumableStep,
          workflowId = facts.liveness?.workflowId?.takeIf(String::isNotBlank),
        )
      }
    }
  }

private fun malformedResultJsonDiagnostics(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerLaunchDiagnostics? = (reconciled as? GoalRunnerReconciledOutcome.Stop)
  ?.let { launchOutcome as? AgentRunLaunchFacts }
  ?.takeIf { facts -> childOutputHasJsonLikeContent(facts.stdout, facts.stderr) }
  ?.takeIf { facts -> terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr) == null }
  ?.let {
    GoalRunnerLaunchDiagnostics(
      diagnosticClass = "malformed_result_json",
      recoverableJsonPresent = false,
      nextSafeAction = "inspect_child_output_then_resume",
    )
  }

private fun terminalJsonObjectWithoutResultPrefix(stdout: String, stderr: String): Map<String, Any?>? {
  val combined = listOf(stdout, stderr)
    .filter(String::isNotBlank)
    .joinToString("\n")
  val candidate = combined
    .takeUnless { it.contains("RESULT:") }
    ?.let(::topLevelJsonObjectCandidates)
    ?.singleOrNull()
  return candidate
    ?.let(JsonSupport::parseObjectOrNull)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.takeIf { it.isImplementationReturnContract() || it.isRuntimeTerminalEnvelope() }
}

private fun childOutputHasJsonLikeContent(stdout: String, stderr: String): Boolean =
  listOf(stdout, stderr).any { output -> output.contains('{') || output.contains('}') || output.contains("RESULT:") }

private fun Map<String, Any?>.isImplementationReturnContract(): Boolean = keys.containsAll(
  setOf(
    "tasks_completed",
    "files_created",
    "files_modified",
    "tests_written",
    "plan_deviation_notes",
    "notes_for_review",
  ),
)

private fun Map<String, Any?>.isRuntimeTerminalEnvelope(): Boolean =
  this["status"]?.toString() in setOf("complete", "completed", "blocked", "failed", "timeout", "timed_out") &&
    this["workflow_id"]?.toString().orEmpty().isNotBlank()

private fun topLevelJsonObjectCandidates(text: String): List<String> {
  val candidates = mutableListOf<String>()
  var depth = 0
  var start = -1
  var inString = false
  var escaped = false
  text.forEachIndexed { index, char ->
    when {
      escaped -> escaped = false
      inString && char == '\\' -> escaped = true
      char == '"' -> inString = !inString
      inString -> Unit
      char == '{' -> {
        if (depth == 0) {
          start = index
        }
        depth += 1
      }
      char == '}' && depth > 0 -> {
        depth -= 1
        if (depth == 0 && start >= 0) {
          candidates += text.substring(start, index + 1)
          start = -1
        }
      }
    }
  }
  return candidates
}

private data class GoalRunnerIterationResult(
  val state: GoalRunnerManifestState,
  val report: GoalRunnerRunReport? = null,
)
