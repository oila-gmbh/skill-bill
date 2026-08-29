package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.time.Clock

internal class GoalRunnerIterationOutcome(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val finalization: GoalRunnerFinalization,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  private val progressReader: GoalRunnerProgressReader,
  private val clock: Clock,
  private val validationQualityRetries: MutableMap<Int, Int>,
  private val pendingReAttemptCause: MutableMap<Int, String>,
  private val pendingCausingLoopEntry: MutableMap<Int, String>,
) {
  fun stoppedIteration(
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
            progress = knownWorkflowId?.let { workflowId -> progressReader.safeProgress(workflowId, request) },
            liveness = stoppedOutcome.liveness,
          )
        },
        workflowId = knownWorkflowId,
        lastResumableStep = stoppedOutcome.lastResumableStep,
      ),
    )
  }

  fun completedIteration(
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
    finalization.pruneCompletedCheckpointRefs(completed, subtaskId, reconciled, request, observability)
    finalization.deleteCompletedSubtaskSpecScratch(completed.manifest, subtaskId, request)
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

  fun safeProgress(workflowId: String, request: GoalRunnerRunRequest): GoalRunnerWorkflowProgress? =
    progressReader.safeProgress(workflowId, request)

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
    val progress = progressReader.safeProgress(workflowId, request)
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

  private fun markChildWorkflowBlockedIfNeeded(
    reconciled: GoalRunnerReconciledOutcome.Stop,
    knownWorkflowId: String?,
    request: GoalRunnerRunRequest,
  ): GoalRunnerReconciledOutcome.Stop {
    if (knownWorkflowId == null || reconciled.reason !in CHILD_WORKFLOW_BLOCK_REASONS) {
      return reconciled
    }
    val progress = progressReader.safeProgress(knownWorkflowId, request)
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
        progress = progressReader.safeProgress(reconciled.workflowId, request),
        finalReconciledResult = "complete commit=${reconciled.commitSha}",
        attemptDurationMillis = attemptStartMillis?.let { clock.millis() - it },
      ),
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
}
