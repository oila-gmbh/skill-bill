package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest

internal class GoalRunnerGoalLoop(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalPlanningSweep: GoalPlanningSweep,
  private val finalization: GoalRunnerFinalization,
  private val selectedSubtaskLoop: GoalRunnerSelectedSubtaskLoop,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val progressReader: GoalRunnerProgressReader,
) {
  fun driveGoalLoop(
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
      val pause = pauseBoundary.pauseBeforeLaunch(state, request)
      if (pause != null) {
        state = pause.state
        terminalReport = pause.report
      } else {
        val selection = GoalRunnerPlanner.selectNext(state.manifest)
        when (selection) {
          is GoalRunnerSelection.Done -> terminalReport = finalization.finalizeGoal(state, request, attempted, ledger)
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
            val result = selectedSubtaskLoop.runSelectedSubtask(
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
          progress = progressReader.safeProgress(workflowId, request),
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
}
