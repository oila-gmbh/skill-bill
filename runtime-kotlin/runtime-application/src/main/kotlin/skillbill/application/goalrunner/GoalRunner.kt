@file:Suppress("LargeClass", "LongParameterList", "TooManyFunctions")

package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.featuretask.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.pruneCompletedSubtaskCheckpointRefs
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunPreparation
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.generateWorkflowId
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.GoalRunnerOutcomeReconciler
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStopReport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAction
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
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
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.SpecScratchStore
import skillbill.ports.workflow.UnavailableSpecScratchStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.stagePaths
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

private val RUNTIME_WORKFLOW_ID_PREFIX: String = WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix

internal fun goalRepositoryIdentity(repoRoot: Path): String {
  val canonical = runCatching { repoRoot.toRealPath() }
    .getOrElse { repoRoot.toAbsolutePath().normalize() }
  return "repo-root-realpath-v1:$canonical"
}

private fun GoalRunnerManifestStore.effectiveAgentAddonSelection(
  parentWorkflowId: String,
  request: GoalRunnerRunRequest,
): AgentAddonSelection = request.agentAddonSelection.persisted
  .takeUnless { it.entries.isEmpty() }
  ?: reviewPolicy(parentWorkflowId, request.dbPathOverride)?.agentAddonSelection
  ?: AgentAddonSelection()

internal data class GoalRunnerEffectiveReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String?,
)

internal fun effectiveGoalRunnerReviewPolicy(
  requestedReviewMode: CodeReviewExecutionMode?,
  requestedParallelReviewAgent: String?,
  persisted: GoalRunnerReviewPolicy?,
): GoalRunnerEffectiveReviewPolicy = GoalRunnerEffectiveReviewPolicy(
  codeReviewMode = requestedReviewMode
    ?: persisted?.codeReviewMode
    ?: CodeReviewExecutionMode.DEFAULT,
  parallelReviewAgent = requestedParallelReviewAgent ?: persisted?.parallelReviewAgent,
)

internal fun goalRunnerReviewPolicyMismatch(
  parentWorkflowId: String,
  requestedReviewMode: CodeReviewExecutionMode?,
  requestedParallelReviewAgent: String?,
  persisted: GoalRunnerReviewPolicy,
): String? = when {
  requestedReviewMode != null && persisted.codeReviewMode != requestedReviewMode ->
    "Cannot change code-review mode on goal resume: parent workflow '$parentWorkflowId' " +
      "is pinned to '${persisted.codeReviewMode.wireValue}', not '${requestedReviewMode.wireValue}'."
  requestedParallelReviewAgent != null && persisted.parallelReviewAgent != requestedParallelReviewAgent ->
    "Cannot change parallel-review agent on goal resume: parent workflow '$parentWorkflowId' " +
      "is pinned to '${persisted.parallelReviewAgent ?: "none"}', not '$requestedParallelReviewAgent'."
  else -> null
}

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
  private val clock: java.time.Clock = java.time.Clock.systemUTC(),
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
  private val executionCoordinator: GoalRunnerExecutionCoordinator = GoalRunnerExecutionCoordinator.NONE,
) {
  private val workerRequestHandler = GoalRunnerWorkerRequestHandler(manifestStore, outcomeStore)
  private val reconciler = GoalRunnerLaunchReconciler(manifestStore, subtaskLauncher, outcomeStore, diagnostics)
  private val validationQualityRetries: MutableMap<Int, Int> = mutableMapOf()
  private val pendingReAttemptCause: MutableMap<Int, String> = mutableMapOf()
  private val pendingCausingLoopEntry: MutableMap<Int, String> = mutableMapOf()

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    validationQualityRetries.clear()
    pendingReAttemptCause.clear()
    pendingCausingLoopEntry.clear()
    val loadedState = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return unknownGoal(request.issueKey)
    return try {
      executionCoordinator.runOwned(loadedState.parentWorkflowId, request.dbPathOverride) {
        val state = reconcileStateBeforeRun(loadedState, request)
        when (val preparation = prepareRun(state, request)) {
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
      // The run path must consume the same terminal child evidence as status. Persist the
      // reconciliation through the governed store so the DB remains authority and the manifest
      // is refreshed as its projection; no caller-side manifest editing is involved.
      manifestStore.save(state.copy(manifest = reconciled), request.dbPathOverride)
    }
  }

  private fun prepareRun(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalRunPreparation {
    val persistedControl = manifestStore.bindRepositoryIdentity(
      state.parentWorkflowId,
      goalRepositoryIdentity(request.repoRoot),
      request.dbPathOverride,
    )
    stopAfterPolicyMismatch(state, request, persistedControl)?.let { return it }
    val persistedReviewPolicy = manifestStore.reviewPolicy(state.parentWorkflowId, request.dbPathOverride)
    persistedReviewPolicy?.let { policy ->
      reviewPolicyMismatch(state, request, policy)?.let { return it }
    }
    val effectiveReviewPolicy = persistEffectiveReviewPolicy(state, request, persistedReviewPolicy)
    val effectiveControl = persistEffectiveStopAfterPolicy(state, request, persistedControl)
    val preparedState = resumeForRun(state, request, effectiveControl)
    return GoalRunPreparation.Prepared(
      preparedState,
      request.copy(
        codeReviewMode = effectiveReviewPolicy.codeReviewMode,
        parallelReviewAgent = effectiveReviewPolicy.parallelReviewAgent,
        stopAfterSubtaskId = request.stopAfterSubtaskId ?: persistedControl.stopAfterSubtaskId,
      ),
    )
  }

  private fun stopAfterPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunPreparation.PreparationBlocked? {
    val requested = request.stopAfterSubtaskId ?: return null
    val persisted = persistedControl.stopAfterSubtaskId ?: return null
    if (persisted == requested) return null
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        issueKey = request.issueKey,
        attempted = emptyList(),
        subtaskId = state.manifest.currentSubtaskIntent.subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = "Cannot change stop-after subtask policy on goal resume: parent workflow " +
          "'${state.parentWorkflowId}' is pinned to subtask $persisted.",
        workflowId = state.parentWorkflowId,
        lastResumableStep = "preplan",
      ),
    )
  }

  private fun persistEffectiveReviewPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedReviewPolicy: GoalRunnerReviewPolicy?,
  ): GoalRunnerReviewPolicy {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val effectiveAgentAddonSelection = requestedAgentAddonSelection
      .takeUnless { it.entries.isEmpty() }
      ?: persistedReviewPolicy?.agentAddonSelection
      ?: AgentAddonSelection()
    val effectiveReviewPolicy = effectiveGoalRunnerReviewPolicy(
      request.codeReviewMode,
      request.parallelReviewAgent,
      persistedReviewPolicy,
    )
    val requestedReviewPolicy = GoalRunnerReviewPolicy(
      codeReviewMode = effectiveReviewPolicy.codeReviewMode,
      parallelReviewAgent = effectiveReviewPolicy.parallelReviewAgent,
      agentAddonSelection = effectiveAgentAddonSelection,
    )
    return manifestStore.persistReviewPolicy(
      parentWorkflowId = state.parentWorkflowId,
      policy = requestedReviewPolicy,
      dbPathOverride = request.dbPathOverride,
    )
  }

  private fun persistEffectiveStopAfterPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunnerControlState = if (request.stopAfterSubtaskId != null && persistedControl.stopAfterSubtaskId == null) {
    manifestStore.persistStopAfterSubtask(
      state.parentWorkflowId,
      request.stopAfterSubtaskId,
      request.dbPathOverride,
    )
  } else {
    persistedControl
  }

  private fun resumeForRun(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    effectiveControl: GoalRunnerControlState,
  ): GoalRunnerManifestState {
    val clearsPause = effectiveControl.paused || effectiveControl.pauseRequested
    val resumedState = if (clearsPause) {
      manifestStore.resume(state.parentWorkflowId, request.dbPathOverride) ?: state
    } else {
      state
    }
    return resumedState.copy(
      controlState = if (clearsPause) {
        manifestStore.controlState(state.parentWorkflowId, request.dbPathOverride)
      } else {
        effectiveControl
      },
    )
  }

  private fun reviewPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunPreparation.PreparationBlocked? {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val reason = goalRunnerReviewPolicyMismatch(
      state.parentWorkflowId,
      request.codeReviewMode,
      request.parallelReviewAgent,
      policy,
    ) ?: if (
      requestedAgentAddonSelection.entries.isNotEmpty() &&
      policy.agentAddonSelection != requestedAgentAddonSelection
    ) {
      "Cannot change agent add-on selection on goal resume: parent workflow '${state.parentWorkflowId}' " +
        "has a different durable selection."
    } else {
      return null
    }
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        issueKey = request.issueKey,
        attempted = emptyList(),
        subtaskId = 0,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = state.parentWorkflowId,
        lastResumableStep = "preplan",
      ),
    )
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
    pauseBeforePlanning(state, effectiveRequest)?.let { paused ->
      val pausedReport = requireNotNull(paused.report)
      closeGoalTelemetrySegment(telemetryEmitter, paused.state, pausedReport, attempted)
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
    val loopResult = driveGoalLoop(
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

  private fun driveGoalLoop(
    initialState: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: MutableList<Int>,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    telemetryEmitter: GoalRunnerTelemetryEmitter,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): GoalRunnerIterationResult {
    var state = initialState
    var currentPlanning = planning
    var terminalReport: GoalRunnerRunReport? = preflightPolicyBlockedReport(state, request, ledger)
    while (terminalReport == null) {
      val pause = pauseBeforeLaunch(state, request)
      if (pause != null) {
        state = pause.state
        terminalReport = pause.report
      } else {
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
            val planningHydrationMissing = currentPlanning.identity != null &&
              currentPlanning.hydrationFor(selection.decision.subtask.id) == null
            if (planningHydrationMissing) {
              when (val refreshedPlanning = goalPlanningSweep.prepare(state, request)) {
                is GoalPlanningSweepOutcome.PreparedAll -> currentPlanning = refreshedPlanning
                is GoalPlanningSweepOutcome.Stopped -> {
                  terminalReport = stopped(
                    refreshedPlanning.issueKey,
                    attempted,
                    refreshedPlanning.currentSubtaskId,
                    refreshedPlanning.reason,
                    refreshedPlanning.blockedReason,
                    state.manifest.workflowIdFor(refreshedPlanning.currentSubtaskId),
                    refreshedPlanning.lastResumableStep,
                  )
                  continue
                }
              }
            }
            val result = runSelectedSubtask(
              state,
              selection,
              request,
              attempted,
              observability,
              ledger,
              telemetryEmitter,
              currentPlanning,
            )
            state = result.state
            terminalReport = result.report
          }
        }
      }
      telemetryEmitter.emitNewlyTerminalSubtasks(state.manifest, attempted)
    }
    return GoalRunnerIterationResult(state, requireNotNull(terminalReport))
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

  private fun pauseBeforePlanning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult? = pauseBeforeLaunch(state, request)

  private fun pauseBeforeLaunch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    knownControl: GoalRunnerControlState? = null,
  ): GoalRunnerIterationResult? {
    val control = knownControl ?: manifestStore.controlState(state.parentWorkflowId, request.dbPathOverride)
    if (!control.requiresPauseBoundary(state.manifest)) return null
    val pausedState = manifestStore.pauseAtBoundary(
      state.copy(controlState = control),
      request.dbPathOverride,
    )
    val subtaskId = pausedState.manifest.currentSubtaskIntent.subtaskId
    return GoalRunnerIterationResult(
      state = pausedState,
      report = stopped(
        issueKey = pausedState.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.PAUSED,
        blockedReason = "Goal paused at a durable boundary: ${pausedState.controlState.pauseReason}",
        workflowId = pausedState.manifest.workflowIdFor(subtaskId),
        lastResumableStep = pausedState.manifest.subtasks
          .firstOrNull { it.id == subtaskId }
          ?.lastResumableStep
          .orEmpty()
          .ifBlank { "plan" },
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
    val earlyStop = pauseBeforeLaunch(state, request) ?: goalBranchSetupFailure(state, selection, request)
    return earlyStop?.let(SelectedSubtaskPreparation::Stopped)
      ?: prepareSelectedSubtaskState(state, selection.decision.subtask.id, request, planning)
  }

  private fun prepareSelectedSubtaskState(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): SelectedSubtaskPreparation {
    val baselineCapture = goalReviewBaseline(state, subtaskId, request)
    if (!baselineCapture.ok) {
      return SelectedSubtaskPreparation.Stopped(
        blockedReviewBaselineIteration(
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
      prepareAttemptedLaunch(state, subtaskId, request, reviewBaseline, planning)
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
        SelectedSubtaskPreparation.Stopped(blockedOnRecoveryError(state, subtaskId, error, request))
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
    return pauseBeforeLaunch(state, request, controlState)
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
    stoppedIteration(
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
      completedIteration(state, subtaskId, reconciled, request, observability, ledger, attemptStartMillis)
    is GoalRunnerReconciledOutcome.Stop ->
      stoppedIteration(
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
        safeProgress(workflowId, request),
        observability,
        ledger,
      )
      emitGoalReviewSummaries(refreshed.manifest.issueKey, subtaskId, workflowId, request)
    }
  }

  private fun blockedOnRecoveryError(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    error: Throwable,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val (targetSubtaskId, reason) = when (error) {
      is skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError ->
        error.subtaskId to goalPlanningChildImportConflictBlockedReason(
          state.manifest.issueKey,
          error.subtaskId,
          error,
        )
      else -> throw error
    }
    state.manifest.workflowIdFor(targetSubtaskId)?.takeIf(String::isNotBlank)?.let { workflowId ->
      runCatching {
        outcomeStore.markBlocked(
          workflowId = workflowId,
          blockedReason = reason,
          lastResumableStep = "preplan",
          supervisionEvent = null,
          dbPathOverride = request.dbPathOverride,
        )
      }
    }
    return blockedReviewBaselineIteration(state, targetSubtaskId, reason, request)
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

  private fun goalReviewBaseline(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalSubtaskReviewBaselineResult {
    val existingWorkflowId = state.manifest.workflowIdFor(subtaskId)
    if (existingWorkflowId != null) {
      return runCatching {
        outcomeStore.goalSubtaskReviewState(existingWorkflowId, request.dbPathOverride)
          ?.let { reviewState ->
            GoalSubtaskReviewBaselineResult(
              status = "ok",
              baseline = GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths),
            )
          }
          ?: GoalSubtaskReviewBaselineResult(
            status = "error",
            error =
            "Goal-subtask review state is missing for existing child '$existingWorkflowId'; " +
              "refusing to recapture its immutable baseline.",
          )
      }.getOrElse { error ->
        GoalSubtaskReviewBaselineResult(
          status = "error",
          error =
          "Goal-subtask review persistence is malformed for existing child '$existingWorkflowId': " +
            error.message.orEmpty(),
        )
      }
    }
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
      ?: return GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal subtask '$subtaskId' has no durable child branch for review baseline capture.",
      )
    return gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
  }

  private fun blockedReviewBaselineIteration(
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
        currentStepId = "preplan",
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
        workflowId = state.manifest.workflowIdFor(subtaskId),
        lastResumableStep = "preplan",
      ),
    )
  }

  private fun emitGoalReviewSummaries(
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
    request: GoalRunnerRunRequest,
  ) {
    outcomeStore.unemittedGoalReviewPasses(workflowId, request.dbPathOverride).forEach { pass ->
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskReviewSummary(
          issueKey = issueKey,
          subtaskId = subtaskId,
          passNumber = pass.passNumber,
          verdict = pass.verdict.wireValue,
          findingCount = pass.findings.size,
          unresolvedFindingCount = pass.unresolvedFindingCount,
          findings = pass.findings,
        ),
      )
      check(outcomeStore.acknowledgeGoalReviewPass(workflowId, pass.passNumber, request.dbPathOverride)) {
        "Goal-subtask review summary pass ${pass.passNumber} could not be acknowledged after emission."
      }
    }
  }

  // SKILL-87: pre-assign and persist the child workflow id before launch so the supervisor resolves it
  // from the first tick (and heartbeats fire through a quiet first phase). A subtask that already
  // carries an id is a resume and keeps it; only a first run flows through open-with-this-id.
  // Crash window (F-006): if the process dies after this manifest save but before the child durably
  // creates its workflow_states row, the next run sees the persisted id and routes a `resume <id>`.
  // That degrades gracefully — ensureWorkflowOpen opens a fresh row at the initial step for an absent
  // id (foreignModeWorkflowBlock only fires for a real foreign-mode row), so resume-of-absent-id is
  // equivalent to a fresh open; this fallback is relied upon rather than special-cased.
  private fun prepareAttemptedLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    reviewBaseline: GoalSubtaskReviewBaseline,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): PreparedLaunch {
    val priorWorkflowId = state.manifest.workflowIdFor(subtaskId)
    val subtask = requireNotNull(state.manifest.subtasks.firstOrNull { it.id == subtaskId }) {
      "Goal subtask '$subtaskId' is missing from the decomposition manifest."
    }
    if (subtask.status == "blocked" && priorWorkflowId != null) {
      reopenBlockedChildForOperatorResume(subtaskId, priorWorkflowId, subtask, request)
    }
    val firstRun = priorWorkflowId == null
    val assignedWorkflowId = priorWorkflowId ?: generateWorkflowId(RUNTIME_WORKFLOW_ID_PREFIX)
    val rawSpecPath = requireNotNull(
      subtask.specPath.takeIf(String::isNotBlank),
    ) { "Goal subtask '$subtaskId' has no governed spec path." }
    val canonicalRepository = runCatching { request.repoRoot.toRealPath() }
      .getOrElse { request.repoRoot.toAbsolutePath().normalize() }
    val lexicalSpecPath = Path.of(rawSpecPath).let { path ->
      (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
    }
    val resolvedSpecPath = runCatching { lexicalSpecPath.toRealPath() }.getOrElse { lexicalSpecPath }
    check(resolvedSpecPath.startsWith(canonicalRepository)) {
      "Goal subtask '$subtaskId' governed spec path escapes repository '$canonicalRepository'."
    }
    val governedSpecPath = canonicalRepository.relativize(resolvedSpecPath).joinToString("/")
    val attemptedManifest = state.manifest.withAttemptedSubtask(subtaskId)
      .let { manifest -> if (firstRun) manifest.withWorkflowId(subtaskId, assignedWorkflowId) else manifest }
    val attemptedState = run {
      val branch = attemptedManifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
        ?: attemptedManifest.featureBranch?.takeIf(String::isNotBlank)
        ?: error("Goal subtask '$subtaskId' has no durable branch for review baseline persistence.")
      manifestStore.saveNewChildWorkflow(
        state.copy(manifest = attemptedManifest),
        GoalRunnerChildWorkflowSetup(
          subtaskId = subtaskId,
          workflowId = assignedWorkflowId,
          goalBranch = branch,
          normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
          repositoryIdentity = "repo-root-realpath-v1:$canonicalRepository",
          governedSpecPath = governedSpecPath,
          reviewBaseline = reviewBaseline,
          reviewPolicy = GoalRunnerReviewPolicy(
            codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
            parallelReviewAgent = request.parallelReviewAgent,
            agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
          ),
          planningHydration = planning.hydrationFor(subtaskId),
        ),
        request.dbPathOverride,
      )
    }
    return PreparedLaunch(attemptedState, assignedWorkflowId.takeIf { firstRun })
  }

  private fun reopenBlockedChildForOperatorResume(
    subtaskId: Int,
    workflowId: String,
    subtask: DecompositionSubtask,
    request: GoalRunnerRunRequest,
  ) {
    val phaseId = subtask.lastResumableStep?.takeIf(String::isNotBlank)
      ?: FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    check(
      outcomeStore.reopenBlockedPhaseForOperatorResume(
        workflowId = workflowId,
        preferredPhaseId = phaseId,
        reason = "Operator resumed the goal after a blocked stop at subtask $subtaskId.",
        dbPathOverride = request.dbPathOverride,
      ),
    ) {
      "Goal subtask '$subtaskId' is blocked but child workflow '$workflowId' could not be reopened for resume."
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
    attemptStartMillis: Long? = null,
  ): GoalRunnerIterationResult {
    val knownWorkflowId = state.manifest.knownWorkflowId(subtaskId, reconciled)
    val stoppedOutcome = markChildWorkflowBlockedIfNeeded(reconciled, knownWorkflowId, request)
    val attemptDurationMillis = attemptStartMillis?.let { clock.millis() - it }
    knownWorkflowId?.let { workflowId ->
      recordStoppedLedgerEntries(
        workflowId, state, subtaskId, stoppedOutcome, reconciled,
        launchDiagnostics, attemptDurationMillis, ledger, request,
      )
    }
    val blocked = if (stoppedOutcome.reason in GoalRunnerStopReason.RESUMABLE_STOP_REASONS) {
      state.manifest.withResumableSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
    } else {
      state.manifest.withStoppedSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
    }
    val blockedState = state.copy(manifest = blocked)
    val control = manifestStore.controlState(state.parentWorkflowId, request.dbPathOverride)
    if (!control.pauseRequested && !control.paused) {
      validationRetryIteration(blocked, stoppedOutcome, subtaskId, state, request)
        ?.let { retry -> return retry }
    }
    val saved = if (control.pauseRequested || control.paused) {
      manifestStore.pauseAtBoundary(blockedState.copy(controlState = control), request.dbPathOverride)
    } else {
      manifestStore.save(blockedState, request.dbPathOverride)
    }
    val parentPaused = saved.controlState.paused
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
        reason = if (parentPaused) GoalRunnerStopReason.PAUSED else stoppedOutcome.reason,
        blockedReason = if (parentPaused) {
          "Goal paused at a durable boundary: ${saved.controlState.pauseReason}"
        } else {
          stoppedOutcome.blockedReason.withStopDiagnostics(
            knownWorkflowId = knownWorkflowId,
            progress = knownWorkflowId?.let { workflowId -> safeProgress(workflowId, request) },
            liveness = stoppedOutcome.liveness,
          )
        },
        workflowId = knownWorkflowId,
        lastResumableStep = stoppedOutcome.lastResumableStep,
      ),
    )
  }

  private fun recordStoppedLedgerEntries(
    workflowId: String,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
    reconciled: GoalRunnerReconciledOutcome.Stop,
    launchDiagnostics: GoalRunnerLaunchDiagnostics?,
    attemptDurationMillis: Long?,
    ledger: GoalRunnerLedgerRecorder,
    request: GoalRunnerRunRequest,
  ) {
    val progress = safeProgress(workflowId, request)
    val childLoopIterations = outcomeStore.childWorkflowLoopIterations(workflowId, request.dbPathOverride)
    val reAttemptCause = reAttemptCauseFor(stoppedOutcome.reason, childLoopIterations)
    val causingLoopEntry = causingLoopEntryFor(childLoopIterations)
    ledger.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = workflowId,
        action = stoppedOutcome.reason.toLedgerAction(),
        issueKey = state.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progress,
        blockedReason = stoppedOutcome.blockedReason,
        finalReconciledResult = stoppedOutcome.reason.name.lowercase(),
        stopReason = stoppedOutcome.reason.name.lowercase(),
        diagnosticClass = launchDiagnostics?.diagnosticClass
          ?: confirmedAliveKillDiagnosticClass(reconciled.liveness)
          ?: stoppedOutcome.reason.toDiagnosticClass(),
        recoverableJsonPresent = launchDiagnostics?.recoverableJsonPresent ?: false,
        nextSafeAction = launchDiagnostics?.nextSafeAction ?: recoverySafeAction(
          issueKey = state.manifest.issueKey,
          subtaskId = subtaskId,
          progress = progress,
          fallback = stoppedOutcome.reason.nextSafeAction(),
        ),
        attemptDurationMillis = attemptDurationMillis,
        reAttemptCause = reAttemptCause,
        causingLoopEntry = causingLoopEntry,
        findingsInScope = resolveFindingsLedger(state.manifest.issueKey, request.dbPathOverride)
          ?.findings?.count { it.subtaskId == subtaskId },
      ),
    )
    childLoopIterations.forEach { (loopId, edgeIteration) ->
      ledger.recordBackwardEdgeEntry(
        GoalRunnerBackwardEdge(
          workflowId = workflowId,
          issueKey = state.manifest.issueKey,
          subtaskId = subtaskId,
          loopId = loopId,
          edgeIteration = edgeIteration,
          progress = progress,
        ),
      )
    }
    if (reAttemptCause != null) pendingReAttemptCause[subtaskId] = reAttemptCause
    causingLoopEntry?.let { pendingCausingLoopEntry[subtaskId] = it }
  }

  internal fun reAttemptCauseFor(reason: GoalRunnerStopReason, childLoopIterations: Map<String, Int>): String? {
    val hasRegeneration = childLoopIterations.keys.any {
      FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it)
    }
    return when {
      reason == GoalRunnerStopReason.RECONCILED_RESUMABLE && hasRegeneration -> "regeneration"
      reason == GoalRunnerStopReason.RECONCILED_RESUMABLE -> "crash_resume"
      childLoopIterations.isNotEmpty() -> "backward_edge"
      else -> null
    }
  }

  private fun causingLoopEntryFor(childLoopIterations: Map<String, Int>): String? = childLoopIterations.entries
    .sortedWith(compareBy({ loopReAttemptPriority(it.key) }, { it.key }))
    .firstOrNull()
    ?.let { (loopId, edgeIteration) -> "$loopId:$edgeIteration" }

  private fun loopReAttemptPriority(loopId: String): Int =
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) 0 else 1

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
    val priorRetries = validationQualityRetries[subtaskId] ?: 0
    if (priorRetries >= MAX_VALIDATION_QUALITY_RETRIES) {
      return null
    }
    validationQualityRetries[subtaskId] = priorRetries + 1
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
    attemptStartMillis: Long? = null,
  ): GoalRunnerIterationResult {
    val completedTransition = manifestStore.saveCompletedSubtaskAtBoundary(
      state.copy(manifest = state.manifest.withCompletedSubtask(subtaskId, reconciled)),
      subtaskId,
      request.dbPathOverride,
    )
    val completed = completedTransition.state
    pruneCompletedCheckpointRefs(completed, subtaskId, reconciled, request, observability)
    // Linear mode: the subtask's spec scratch is excluded from the commit, so once its commit is
    // durable (commitSha recorded above) delete that subtask's spec file. The manifest survives — it
    // is live runtime state for the remaining subtasks and is removed only at finalize. Local mode
    // keeps the spec on disk (it was committed). Deletion is failure-isolated and idempotent.
    deleteCompletedSubtaskSpecScratch(completed.manifest, subtaskId, request)
    recordCompletedSubtask(completed, subtaskId, reconciled, request, observability, ledger, attemptStartMillis)
    return if (!completedTransition.paused) {
      GoalRunnerIterationResult(state = completed)
    } else {
      GoalRunnerIterationResult(
        state = completed,
        report = stopped(
          issueKey = completed.manifest.issueKey,
          attempted = emptyList(),
          subtaskId = subtaskId,
          reason = GoalRunnerStopReason.PAUSED,
          blockedReason = "Goal paused at a durable boundary: ${completed.controlState.pauseReason}",
          workflowId = reconciled.workflowId,
          lastResumableStep = reconciled.lastResumableStep,
        ),
      )
    }
  }

  private fun recordCompletedSubtask(
    completed: GoalRunnerManifestState,
    subtaskId: Int,
    reconciled: GoalRunnerReconciledOutcome.Complete,
    request: GoalRunnerRunRequest,
    observability: GoalRunnerObservabilityEmitter,
    ledger: GoalRunnerLedgerRecorder,
    attemptStartMillis: Long?,
  ) {
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
        attemptDurationMillis = attemptStartMillis?.let { clock.millis() - it },
      ),
    )
  }

  private fun pruneCompletedCheckpointRefs(
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

  private fun resolveFindingsLedger(issueKey: String, dbPathOverride: String?): UnaddressedFindingsLedger? {
    val service = unaddressedFindingsLedgerService ?: return null
    return try {
      service.ledger(issueKey, dbPathOverride)
    } catch (_: UnaddressedFindingsLedgerAbsentError) {
      UnaddressedFindingsLedger(issueKey, emptyList())
    } catch (_: InvalidUnaddressedFindingsLedgerSchemaError) {
      null
    }
  }

  private fun finalizeGoal(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    attempted: List<Int>,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport {
    reconcileBeforeFinalization(state, request, ledger)
    val finalState = manifestStore.save(state, request.dbPathOverride)
    commitAllRemainingWorktree(finalState.manifest, request)?.let { reason ->
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

  private fun reconcileBeforeFinalization(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    ledger: GoalRunnerLedgerRecorder,
  ) {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      // SKILL-87: with an empty active set, demand positive staleness evidence so finalize cannot
      // false-kill a subtask that is still live (the prior emptySet reset-only semantics did).
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
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
  }

  /**
   * Finalize implementation changes left behind by mid-run checkpoints. Feature specs are workflow
   * inputs: this sweep never stages them, leftover spec dirt is not a cleanliness failure, and spec
   * files a human operator already committed stay in HEAD. A worktree whose only remaining dirt is
   * under `.feature-specs/` and that is still ahead of `origin/<feature-branch>` is re-pushed rather
   * than treated as done.
   */
  private fun commitAllRemainingWorktree(manifest: DecompositionManifest, request: GoalRunnerRunRequest): String? {
    // Linear scratch is ephemeral and must not be committed; delete it before staging.
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

  private fun commitAndPushDirtyWorktree(
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

  private fun stageCommitAndPushAll(
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

  private fun verifyWorktreeCleanAfterCommitAll(request: GoalRunnerRunRequest): String? {
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

  /**
   * When the worktree is already clean, still push if local [featureBranch] is ahead of origin
   * (F-002: resume after commit-all whose push failed must not open a PR from a stale remote tip).
   */
  private fun pushUnpushedFeatureBranchIfNeeded(featureBranch: String, repoRoot: Path): String? {
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

  private fun requireFeatureBranchForFinalize(featureBranch: String, repoRoot: Path): String? {
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

  // Deletes the parent spec + manifest (the whole decomposition scratch dir). Linear mode only;
  // failure-isolated. Invoked before commit-all so ephemeral Linear scratch is not swept into the
  // final commit, and is a no-op when already deleted.
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
    ledger: UnaddressedFindingsLedger?,
  ): GoalRunnerRunReport.Completed {
    return GoalRunnerRunReport.Completed(
      issueKey = manifest.issueKey,
      attemptedSubtasks = attempted,
      featureName = manifest.featureName,
      pullRequestUrl = pullRequestUrl,
      pullRequestStatus = pullRequestStatus,
      subtasksCompleted = manifest.subtasks.count { it.status == "complete" },
      subtasksPending = manifest.subtasks.count { it.status !in setOf("complete", "skipped", "blocked") },
      subtasksBlocked = manifest.subtasks.count { it.status == "blocked" },
      unaddressedFindingCount = ledger?.findings?.size,
      unaddressedSeverityBreakdown = ledger?.severityBreakdown.orEmpty(),
    )
  }

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
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun subtaskLaunchRequest(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    // SKILL-87: non-null only on a first run; routes the child through open-with-this-id, not resume.
    assignedWorkflowId: String? = null,
    reviewBaseline: GoalSubtaskReviewBaseline? = null,
    spawnAuthorization: AgentRunSpawnAuthorization? = null,
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
    val goalContinuation = goalContinuationContext(issueKey, subtaskId, request, assignedWorkflowId, reviewBaseline)
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
        readOnlyPhase = goalContinuation?.lastResumableStep ==
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        goalContinuation = goalContinuation,
        spawnAuthorization = spawnAuthorization,
      ),
    )
  }

  private fun goalContinuationContext(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    assignedWorkflowId: String?,
    reviewBaseline: GoalSubtaskReviewBaseline?,
  ): SkillRunGoalContinuationContext? {
    val state = manifestStore.loadByIssueKey(issueKey, request.dbPathOverride, request.repoRoot) ?: return null
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
    val subtask = state.manifest.subtasks.firstOrNull { it.id == subtaskId }
    val specPath = subtask?.specPath?.takeIf(String::isNotBlank)
    return if (branch != null && subtask != null && specPath != null) {
      // SKILL-87: a freshly pre-assigned id is an open, not a resume — drop it from childWorkflowId so
      // the command builder emits `run --workflow-id`; a pre-existing id stays the resume id.
      val childWorkflowId = if (assignedWorkflowId == null) {
        state.manifest.workflowIdFor(subtaskId)
      } else {
        null
      }
      SkillRunGoalContinuationContext(
        parentIssueKey = issueKey,
        subtaskId = subtaskId,
        goalBranch = branch,
        suppressPr = true,
        specPath = specPath,
        parentWorkflowId = state.parentWorkflowId,
        lastResumableStep = subtask.lastResumableStep?.takeIf(String::isNotBlank),
        childWorkflowId = childWorkflowId,
        assignedWorkflowId = assignedWorkflowId,
        codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
        validationDepth = ValidationDepth.FULL,
        qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, subtaskId),
        parallelReviewAgent = request.parallelReviewAgent,
        agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
        reviewBaseline = state.manifest.workflowIdFor(subtaskId)
          ?.let { workflowId -> outcomeStore.goalSubtaskReviewState(workflowId, request.dbPathOverride) }
          ?.let { reviewState ->
            GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths)
          }
          ?: reviewBaseline,
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
    // The foreground process result is already the bounded completion signal. A terminal child
    // outcome that was not visible by this point is reconciled as resumable; the parent never
    // waits, reloads, or launches a retry to manufacture a later result.
    return launchReconciliation(refreshed, reconciled, launchOutcome, subtaskId, request)
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
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = false),
      repoRoot = request.repoRoot,
      dbPathOverride = request.dbPathOverride,
    )[subtaskId]
  }
}

private const val FEATURE_SPEC_ROOT = ".feature-specs"
private const val GIT_PORCELAIN_MIN_LENGTH = 4
private const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3
private const val MAX_VALIDATION_QUALITY_RETRIES = 3
private const val MAX_REPORTED_FINALIZE_DIRTY_PATHS = 10

private fun isFeatureSpecPath(path: String): Boolean {
  val normalized = path.trim().trimEnd('/').removeSurrounding("\"").removePrefix("./")
  val dotted = if (normalized.startsWith(".")) normalized else ".$normalized"
  return dotted == FEATURE_SPEC_ROOT || dotted.startsWith("$FEATURE_SPEC_ROOT/")
}

private val PROTECTED_GOAL_BRANCHES: Set<String> = setOf("main", "master", "trunk")
private val CHILD_WORKFLOW_BLOCK_REASONS: Set<GoalRunnerStopReason> = setOf(
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
)

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
      stderrExcerpt = stderrExcerpt(stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS),
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
          livenessState = snapshot.livenessState,
          aliveAtKill = snapshot.livenessState == GoalRunnerLivenessState.WORKING ||
            snapshot.livenessState == GoalRunnerLivenessState.PROGRESSING,
        )
      },
    )
    is UnsupportedAgentRunLaunch -> GoalRunnerLaunchFacts(spawnFailed = true)
  }

private fun DecompositionManifest.withAttemptedSubtask(subtaskId: Int): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId && subtask.status in setOf("blocked", "pending")) {
      subtask.copy(status = "in_progress", blockedReason = null)
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.withWorkflowId(subtaskId: Int, workflowId: String): DecompositionManifest = copy(
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) subtask.copy(workflowId = workflowId) else subtask
  },
)

private fun DecompositionManifest.knownWorkflowId(subtaskId: Int, outcome: GoalRunnerReconciledOutcome.Stop): String? =
  outcome.workflowId ?: subtasks.firstOrNull { it.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)

private fun DecompositionManifest.withCompletedSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Complete,
): DecompositionManifest {
  val updated = copy(
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
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

// A crash-reconciled child keeps the subtask resumable (not blocked): status stays in_progress with a
// resume intent at its recorded step, so `skill-bill goal <key>` resume continues without clearing.
private fun DecompositionManifest.withResumableSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: String? = outcome.workflowId,
): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "in_progress",
        workflowId = knownWorkflowId ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = null,
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

// SKILL-87: [openWithAssignedId] is the pre-assigned id only on a first run (open-with-this-id), null
// on a resume where the persisted manifest id is the resume id.
private data class PreparedLaunch(
  val state: GoalRunnerManifestState,
  val openWithAssignedId: String?,
)

private sealed interface SelectedSubtaskPreparation {
  class Ready(
    val subtaskId: Int,
    val attemptedState: GoalRunnerManifestState,
    val openWithAssignedId: String?,
    val reviewBaseline: GoalSubtaskReviewBaseline,
  ) : SelectedSubtaskPreparation

  class Stopped(val result: GoalRunnerIterationResult) : SelectedSubtaskPreparation
}

private sealed interface SelectedSubtaskLaunch {
  class Completed(
    val reconciliation: GoalRunnerLaunchReconciliation,
    val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
    val attemptStartMillis: Long,
  ) : SelectedSubtaskLaunch

  class Stopped(val result: GoalRunnerIterationResult) : SelectedSubtaskLaunch
}

private data class LaunchRecordingContext(
  val workflowId: String,
  val refreshed: GoalRunnerManifestState,
  val subtaskId: Int,
  val selection: GoalRunnerSelection.Run,
  val launchReconciliation: GoalRunnerLaunchReconciliation,
  val reAttemptCause: String? = null,
  val causingLoopEntry: String? = null,
)

private fun recordLaunchObservabilityAndLedger(
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
      reAttemptCause = context.reAttemptCause,
      causingLoopEntry = context.causingLoopEntry,
    ),
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

private fun confirmedAliveKillDiagnosticClass(liveness: GoalRunnerLivenessSnapshot?): String? =
  if (liveness?.aliveAtKill == true) GoalRunnerLaunchFacts.DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL else null

private fun GoalRunnerStopReason.nextSafeAction(): String = when (this) {
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
  GoalRunnerStopReason.RECONCILED_RESUMABLE,
  GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
  GoalRunnerStopReason.PAUSED,
  -> "resume_from_last_resumable_step"
  GoalRunnerStopReason.FAILED -> "inspect_child_output_then_resume"
  else -> "inspect_blocked_reason"
}

internal fun recoverySafeAction(
  issueKey: String,
  subtaskId: Int,
  progress: GoalRunnerWorkflowProgress?,
  fallback: String,
): String = when (classifyDurableChild(progress)) {
  DurableChildRecoveryClass.RESUMABLE -> "resume_from_last_resumable_step"
  DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL -> scopedChildRecoveryCommand(issueKey, subtaskId)
  else -> fallback
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
