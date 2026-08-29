package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.model.GoalRunPreparation
import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock

@Inject
class GoalRunner(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val pullRequestPort: GoalPullRequestPort,
  private val goalPlanningSweep: GoalPlanningSweep = GoalPlanningSweep.NONE,
  private val specScratchStore: SpecScratchStore = UnavailableSpecScratchStore,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val telemetry: GoalLifecycleTelemetryEmitter = GoalLifecycleTelemetryEmitter.NONE,
  private val clock: Clock = Clock.systemUTC(),
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
  private val executionCoordinator: GoalRunnerExecutionCoordinator = GoalRunnerExecutionCoordinator.NONE,
) {
  private val validationQualityRetries: MutableMap<Int, Int> = mutableMapOf()
  private val pendingReAttemptCause: MutableMap<Int, String> = mutableMapOf()
  private val pendingCausingLoopEntry: MutableMap<Int, String> = mutableMapOf()
  private val workerRequestHandler = GoalRunnerWorkerRequestHandler(manifestStore, outcomeStore)
  private val reconciler = GoalRunnerLaunchReconciler(manifestStore, subtaskLauncher, outcomeStore, diagnostics)
  private val progressReader = GoalRunnerProgressReader(outcomeStore)
  private val pauseBoundary = GoalRunnerPauseBoundary(manifestStore)
  private val runPreparation = GoalRunnerRunPreparation(manifestStore)
  private val finalization = GoalRunnerFinalization(
    manifestStore,
    outcomeStore,
    pullRequestPort,
    specScratchStore,
    gitOperations,
    diagnostics,
    unaddressedFindingsLedgerService,
    progressReader,
  )
  private val iterationOutcome = GoalRunnerIterationOutcome(
    manifestStore,
    outcomeStore,
    finalization,
    unaddressedFindingsLedgerService,
    progressReader,
    clock,
    validationQualityRetries,
    pendingReAttemptCause,
    pendingCausingLoopEntry,
  )
  private val launchPrepare = GoalRunnerSubtaskLaunchPrepare(manifestStore, outcomeStore, gitOperations)
  private val selectedSubtaskLoop = GoalRunnerSelectedSubtaskLoop(
    manifestStore,
    subtaskLauncher,
    reconciler,
    workerRequestHandler,
    iterationOutcome,
    pauseBoundary,
    launchPrepare,
    clock,
    pendingReAttemptCause,
    pendingCausingLoopEntry,
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
        issueKey = sweepOutcome.issueKey,
        attempted = emptyList(),
        subtaskId = sweepOutcome.currentSubtaskId,
        reason = sweepOutcome.reason,
        blockedReason = sweepOutcome.blockedReason,
        workflowId = null,
        lastResumableStep = sweepOutcome.lastResumableStep,
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
      state,
      effectiveRequest,
      attempted,
      observability,
      ledger,
      telemetryEmitter,
      sweepOutcome as GoalPlanningSweepOutcome.PreparedAll,
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
