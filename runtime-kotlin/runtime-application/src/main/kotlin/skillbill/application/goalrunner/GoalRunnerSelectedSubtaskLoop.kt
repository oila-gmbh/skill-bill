package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import java.time.Clock

internal class GoalRunnerSelectedSubtaskLoop(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val reconciler: GoalRunnerLaunchReconciler,
  private val workerRequestHandler: GoalRunnerWorkerRequestHandler,
  private val iterationOutcome: GoalRunnerIterationOutcome,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val launchPrepare: GoalRunnerSubtaskLaunchPrepare,
  private val clock: Clock,
  private val pendingReAttemptCause: MutableMap<Int, String>,
  private val pendingCausingLoopEntry: MutableMap<Int, String>,
) {
  fun runSelectedSubtask(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): GoalRunnerIterationResult {
    val prepared = when (val result = prepareSelectedSubtask(state, selection, request, planning)) {
      is SelectedSubtaskPreparation.Stopped -> return result.result
      is SelectedSubtaskPreparation.Ready -> result
    }
    val launch = when (
      val result = authorizeAndLaunchSelectedSubtask(
        prepared,
        selection,
        request,
        attempted,
        telemetryEmitter,
      )
    ) {
      is SelectedSubtaskLaunch.Stopped -> return result.result
      is SelectedSubtaskLaunch.Completed -> result
    }
    val refreshed = launch.workerRequestResult.state
    val reconciled = launch.reconciliation.reconciled
    val reAttemptCause = pendingReAttemptCause.remove(prepared.subtaskId)
    val causingLoopEntry = pendingCausingLoopEntry.remove(prepared.subtaskId)
    recordPostLaunchState(
      refreshed,
      prepared.subtaskId,
      selection,
      launch.reconciliation,
      request,
      observability,
      ledger,
      reAttemptCause,
      causingLoopEntry,
    )
    return dispatchWorkerResult(
      refreshed, prepared.subtaskId, reconciled, launch.workerRequestResult, launch.reconciliation,
      request, attempted, observability, ledger, launch.attemptStartMillis,
    )
  }

  private fun prepareSelectedSubtask(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): SelectedSubtaskPreparation {
    val earlyStop = pauseBoundary.pauseBeforeLaunch(state, request)
      ?: launchPrepare.goalBranchSetupFailure(state, selection, request)
    return earlyStop?.let(SelectedSubtaskPreparation::Stopped)
      ?: prepareSelectedSubtaskState(state, selection.decision.subtask.id, request, planning)
  }

  private fun prepareSelectedSubtaskState(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): SelectedSubtaskPreparation {
    val baselineCapture = launchPrepare.goalReviewBaseline(state, subtaskId, request)
    if (!baselineCapture.ok) {
      return SelectedSubtaskPreparation.Stopped(
        launchPrepare.blockedReviewBaselineIteration(
          state,
          subtaskId,
          "Could not capture the goal-subtask review baseline before implementation. " +
            "Refusing to substitute a branch-wide scope. ${baselineCapture.error}",
          request,
        ),
      )
    }
    val reviewBaseline = requireNotNull(baselineCapture.baseline)
    return runCatching {
      launchPrepare.prepareAttemptedLaunch(state, subtaskId, request, reviewBaseline, planning)
    }.fold(
      onSuccess = { prepared ->
        SelectedSubtaskPreparation.Ready(
          subtaskId = subtaskId,
          attemptedState = prepared.state,
          openWithAssignedId = prepared.openWithAssignedId,
          reviewBaseline = reviewBaseline,
        )
      },
      onFailure = { error ->
        SelectedSubtaskPreparation.Stopped(
          launchPrepare.blockedOnRecoveryError(state, subtaskId, error, request),
        )
      },
    )
  }

  private fun authorizeAndLaunchSelectedSubtask(
    prepared: SelectedSubtaskPreparation.Ready,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
  ): SelectedSubtaskLaunch {
    val subtaskId = prepared.subtaskId
    val launchAuthorization = manifestStore.authorizeSubtaskLaunch(
      prepared.attemptedState,
      subtaskId,
      request.dbPathOverride,
    )
    if (!launchAuthorization.authorized) {
      return SelectedSubtaskLaunch.Stopped(
        deniedLaunchPause(prepared, request, launchAuthorization.controlState),
      )
    }
    attempted += subtaskId
    emitSubtaskStarted(prepared.attemptedState, subtaskId, selection, request, telemetryEmitter)
    val attemptStartMillis = clock.millis()
    val (launchReconciliation, workerRequestResult) = try {
      launchSubtaskWithWorkerResult(
        prepared.attemptedState,
        subtaskId,
        request,
        prepared.openWithAssignedId,
        prepared.reviewBaseline,
        launchAuthorization.spawnAuthorization,
      )
    } catch (denied: GoalRunnerLaunchAuthorizationDeniedException) {
      return SelectedSubtaskLaunch.Stopped(
        deniedLaunchPause(prepared, request, denied.controlState),
      )
    }
    return SelectedSubtaskLaunch.Completed(
      reconciliation = launchReconciliation,
      workerRequestResult = workerRequestResult,
      attemptStartMillis = attemptStartMillis,
    )
  }

  private fun deniedLaunchPause(
    prepared: SelectedSubtaskPreparation.Ready,
    request: GoalRunnerRunRequest,
    controlState: GoalRunnerControlState,
  ): GoalRunnerIterationResult {
    val state = prepared.openWithAssignedId?.let { workflowId ->
      manifestStore.deleteIncompatibleChildWorkflow(
        state = prepared.attemptedState,
        subtaskId = prepared.subtaskId,
        workflowId = workflowId,
        dbPathOverride = request.dbPathOverride,
      )
    } ?: prepared.attemptedState
    return pauseBoundary.pauseBeforeLaunch(state, request, controlState)
      ?: error(
        "Subtask ${prepared.subtaskId} launch authorization was denied without a durable pause boundary.",
      )
  }

  private fun dispatchWorkerResult(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome,
    workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
    launchReconciliation: GoalRunnerLaunchReconciliation,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    attemptStartMillis: Long?,
  ): GoalRunnerIterationResult = workerRequestResult.operatorConfirmationStop?.let { stop ->
    iterationOutcome.stoppedIteration(
      state,
      subtaskId,
      stop,
      request,
      attempted,
      observability,
      ledger,
      attemptStartMillis = attemptStartMillis,
    )
  } ?: when (reconciled) {
    is GoalRunnerReconciledOutcome.Complete ->
      iterationOutcome.completedIteration(state, subtaskId, reconciled, request, observability, ledger, attemptStartMillis)
    is GoalRunnerReconciledOutcome.Stop ->
      iterationOutcome.stoppedIteration(
        state, subtaskId, reconciled, request, attempted, observability, ledger,
        launchReconciliation.diagnostics, attemptStartMillis = attemptStartMillis,
      )
  }

  private fun recordPostLaunchState(
    refreshed: GoalRunnerManifestState,
    subtaskId: Int,
    selection: GoalRunnerSelection.Run,
    reconciliation: GoalRunnerLaunchReconciliation,
    request: GoalRunnerRunRequest,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    reAttemptCause: String? = null,
    causingLoopEntry: String? = null,
  ) {
    refreshed.manifest.workflowIdFor(subtaskId)?.let { workflowId ->
      recordLaunchObservabilityAndLedger(
        LaunchRecordingContext(
          workflowId,
          refreshed,
          subtaskId,
          selection,
          reconciliation,
          reAttemptCause,
          causingLoopEntry,
        ),
        iterationOutcome.safeProgress(workflowId, request),
        observability,
        ledger,
      )
      launchPrepare.emitGoalReviewSummaries(refreshed.manifest.issueKey, subtaskId, workflowId, request)
    }
  }

  private fun launchSubtaskWithWorkerResult(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    assignedWorkflowId: String?,
    reviewBaseline: GoalSubtaskReviewBaseline,
    spawnAuthorization: AgentRunSpawnAuthorization?,
  ): Pair<GoalRunnerLaunchReconciliation, GoalRunnerWorkerRequestHandlingResult> {
    val launchReconciliation = launchAndReconcileSubtask(
      state,
      subtaskId,
      request,
      assignedWorkflowId,
      reviewBaseline,
      spawnAuthorization,
    )
    val workerRequestResult = workerRequestHandler.handle(
      state = launchReconciliation.refreshed,
      launchOutcome = launchReconciliation.launchOutcome,
      subtaskId = subtaskId,
      request = request,
    )
    return Pair(launchReconciliation, workerRequestResult)
  }

  private fun launchAndReconcileSubtask(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    assignedWorkflowId: String?,
    reviewBaseline: GoalSubtaskReviewBaseline,
    spawnAuthorization: AgentRunSpawnAuthorization?,
  ): GoalRunnerLaunchReconciliation {
    val launchOutcome = subtaskLauncher.launch(
      reconciler.subtaskLaunchRequest(
        state.manifest.issueKey,
        subtaskId,
        request,
        assignedWorkflowId = assignedWorkflowId,
        reviewBaseline = reviewBaseline,
        spawnAuthorization = spawnAuthorization,
      ),
    )
    return reconciler.reconcileLaunchOutcome(state, launchOutcome, subtaskId, request)
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
          iterationOutcome.safeProgress(workflowId, request)?.currentStepId
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
}
