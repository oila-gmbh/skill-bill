package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.model.GoalRunPreparation
import skillbill.application.goalrunner.model.GoalRunnerDeps
import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

@Inject
class GoalRunner(deps: GoalRunnerDeps) {
  private val manifestStore = deps.manifestStore
  private val subtaskLauncher = deps.subtaskLauncher
  private val outcomeStore = deps.outcomeStore
  private val pullRequestPort = deps.pullRequestPort
  private val goalPlanningSweep = deps.goalPlanningSweep
  private val specScratchStore = deps.specScratchStore
  private val gitOperations = deps.gitOperations
  private val telemetry = deps.telemetry
  private val clock = deps.clock
  private val diagnostics = deps.diagnostics
  private val unaddressedFindingsLedgerService = deps.unaddressedFindingsLedgerService
  private val executionCoordinator = deps.executionCoordinator
  private val validationQualityRetries: MutableMap<Int, Int> = mutableMapOf()
  private val pendingReAttemptCause: MutableMap<Int, String> = mutableMapOf()
  private val pendingCausingLoopEntry: MutableMap<Int, String> = mutableMapOf()
  private val workerRequestHandler = GoalRunnerWorkerRequestHandler(manifestStore, outcomeStore)
  private val reconciler = GoalRunnerLaunchReconciler(manifestStore, outcomeStore, diagnostics)
  private val progressReader = GoalRunnerProgressReader(outcomeStore)
  private val pauseBoundary = GoalRunnerPauseBoundary(manifestStore)
  private val runPreparation = GoalRunnerRunPreparation(manifestStore)
  private val finalization = GoalRunnerFinalization(
    GoalRunnerFinalizationDeps(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      pullRequestPort = pullRequestPort,
      specScratchStore = specScratchStore,
      gitOperations = gitOperations,
      diagnostics = diagnostics,
      unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
      progressReader = progressReader,
    ),
  )
  private val pendingState = GoalRunnerIterationPendingState(
    validationQualityRetries = validationQualityRetries,
    pendingReAttemptCause = pendingReAttemptCause,
    pendingCausingLoopEntry = pendingCausingLoopEntry,
  )
  private val iterationOutcome = GoalRunnerIterationOutcome(
    GoalRunnerIterationOutcomeDeps(
      manifestStore,
      outcomeStore,
      finalization,
      unaddressedFindingsLedgerService,
      progressReader,
      clock,
    ),
    pendingState,
  )
  private val launchPrepare = GoalRunnerSubtaskLaunchPrepare(manifestStore, outcomeStore, gitOperations)
  private val selectedSubtaskLoop = GoalRunnerSelectedSubtaskLoop(
    GoalRunnerSelectedSubtaskLoopDeps(
      manifestStore,
      subtaskLauncher,
      reconciler,
      workerRequestHandler,
      iterationOutcome,
      pauseBoundary,
      launchPrepare,
      clock,
      pendingState,
    ),
  )
  private val goalLoop = GoalRunnerGoalLoop(
    manifestStore,
    goalPlanningSweep,
    finalization,
    selectedSubtaskLoop,
    pauseBoundary,
    progressReader,
  )

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    validationQualityRetries.clear()
    pendingReAttemptCause.clear()
    pendingCausingLoopEntry.clear()
    val loadedState = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return unknownGoal(request.issueKey)
    return try {
      executionCoordinator.runOwned(loadedState.parentWorkflowId, request.dbPathOverride) {
        val state = reconcileStateBeforeRun(loadedState, request)
        when (val preparation = runPreparation.prepareRun(state, request)) {
          is GoalRunPreparation.PreparationBlocked -> preparation.report
          is GoalRunPreparation.Prepared -> runPrepared(preparation)
        }
      }
    } catch (alreadyRunning: GoalRunnerExecutionAlreadyRunningException) {
      stopped(
        StoppedReportArgs(
          issueKey = loadedState.manifest.issueKey,
          attempted = emptyList(),
          subtaskId = loadedState.manifest.currentSubtaskIntent.subtaskId,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = alreadyRunning.message.orEmpty(),
          workflowId = loadedState.manifest.workflowIdFor(loadedState.manifest.currentSubtaskIntent.subtaskId),
          lastResumableStep = loadedState.manifest.subtasks
            .firstOrNull { it.id == loadedState.manifest.currentSubtaskIntent.subtaskId }
            ?.lastResumableStep
            .orEmpty()
            .ifBlank { "plan" },
        ),
      )
    }
  }

  private fun reconcileStateBeforeRun(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerManifestState {
    val reconciled = reconcileGoalManifest(
      manifest = state.manifest,
      dbPathOverride = request.dbPathOverride,
      authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey, request.dbPathOverride),
      acceptances = manifestStore.outOfBandAcceptances(state.parentWorkflowId, request.dbPathOverride),
      outcomeStore = outcomeStore,
    )
    return if (reconciled == state.manifest) {
      state
    } else {
      manifestStore.save(state.copy(manifest = reconciled), request.dbPathOverride)
    }
  }

  private fun runPrepared(preparation: GoalRunPreparation.Prepared): GoalRunnerRunReport {
    var state = preparation.state
    val effectiveRequest = preparation.request
    val attempted = mutableListOf<Int>()
    val observability = GoalRunnerObservabilityEmitter(outcomeStore, effectiveRequest)
    val ledger = GoalRunnerLedgerRecorder(outcomeStore, effectiveRequest, diagnostics)
    effectiveRequest.eventSink.emit(GoalRunnerRunEvent.Started(state.manifest.issueKey))
    val telemetryEmitter =
      GoalRunnerTelemetryEmitter(telemetry, clock, state, effectiveRequest.dbPathOverride).also { it.goalStarted() }
    pauseBoundary.pauseBeforeLaunch(state, effectiveRequest)?.let { paused ->
      val pausedReport = requireNotNull(paused.report)
      closeGoalTelemetrySegment(telemetryEmitter, state, pausedReport, attempted)
      return pausedReport
    }
    val sweepOutcome = goalPlanningSweep.prepare(state, effectiveRequest)
    if (sweepOutcome is GoalPlanningSweepOutcome.Stopped) {
      val planningStop = stopped(
        StoppedReportArgs(
          issueKey = sweepOutcome.issueKey,
          attempted = emptyList(),
          subtaskId = sweepOutcome.currentSubtaskId,
          reason = sweepOutcome.reason,
          blockedReason = sweepOutcome.blockedReason,
          workflowId = null,
          lastResumableStep = sweepOutcome.lastResumableStep,
        ),
      )
      effectiveRequest.eventSink.emit(
        GoalRunnerRunEvent.SubtaskStopped(
          issueKey = sweepOutcome.issueKey,
          subtaskId = sweepOutcome.currentSubtaskId,
          reason = sweepOutcome.reason.name.lowercase(),
          blockedReason = sweepOutcome.blockedReason,
          currentStepId = sweepOutcome.lastResumableStep,
        ),
      )
      closeGoalTelemetrySegment(telemetryEmitter, state, planningStop, attempted)
      return planningStop
    }
    val loopResult = goalLoop.driveGoalLoop(
      DriveGoalLoopArgs(
        initialState = state,
        request = effectiveRequest,
        attempted = attempted,
        observability = observability,
        ledger = ledger,
        telemetryEmitter = telemetryEmitter,
        planning = sweepOutcome as GoalPlanningSweepOutcome.PreparedAll,
      ),
    )
    state = loopResult.state
    val finalReport = requireNotNull(loopResult.report)
    closeGoalTelemetrySegment(telemetryEmitter, state, finalReport, attempted)
    emitCompletedGoalEvent(effectiveRequest, finalReport)
    return finalReport
  }

  private fun emitCompletedGoalEvent(request: GoalRunnerRunRequest, finalReport: GoalRunnerRunReport) {
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
  }

  private fun closeGoalTelemetrySegment(
    telemetryEmitter: GoalRunnerTelemetryEmitter,
    state: GoalRunnerManifestState,
    finalReport: GoalRunnerRunReport,
    attempted: List<Int>,
  ) {
    telemetryEmitter.let { emitter ->
      emitter.emitNewlyTerminalSubtasks(state.manifest, attempted)
      emitter.goalFinished(state.manifest, finalReport)
      if (finalReport is GoalRunnerRunReport.Completed) {
        emitter.goalIssueFinished(state.manifest, finalReport)
      }
    }
  }
}
