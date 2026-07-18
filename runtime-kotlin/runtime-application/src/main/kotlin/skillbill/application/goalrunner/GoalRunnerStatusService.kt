package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.agentAttributionFromPhaseState
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerResetSnapshot
import skillbill.application.model.GoalRunnerResetSubtaskSnapshot
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.let { loadedState ->
        val activeWorkflowIds = loadedState.manifest.activeWorkflowIds()
        val authoritativeOutcomes = outcomeStore.reconcileAuthoritativeOutcomes(
          issueKey = loadedState.manifest.issueKey,
          activeWorkflowIds = activeWorkflowIds,
          gate = GoalRunnerReconcileGate(allowInactiveReconciliation = false),
          dbPathOverride = request.dbPathOverride,
        )
        val state = persistReconciledManifestIfChanged(loadedState, request, authoritativeOutcomes)
        val currentSubtask = state.manifest.subtasks.firstOrNull { subtask ->
          subtask.id == state.manifest.currentSubtaskIntent.subtaskId
        }
        val progress = currentSubtask
          ?.workflowId
          ?.takeIf(String::isNotBlank)
          ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) }
        val planningBlock = currentSubtask?.takeIf { subtask ->
          subtask.status == "blocked" && subtask.lastResumableStep in setOf("preplan", "plan")
        }
        GoalRunnerStatusProjector.project(
          manifest = state.manifest,
          activeAgent = resolveActiveAgent(currentSubtask, request.dbPathOverride),
          extras = GoalRunnerStatusProjectionExtras(
            planning = manifestStore.planningStatus(
              state.parentWorkflowId,
              state.manifest.subtasks.filter { it.status != "skipped" }.map { it.id },
              planningBlock?.id,
              planningBlock?.blockedReason,
              request.dbPathOverride,
            ),
            currentStepOverride = progress?.currentStepId,
            latestLivenessSignal = progress?.latestLivenessSignal,
            latestObservabilityEvent = progress?.latestGoalObservabilityEvent?.toStatusMap(),
            requestedDiffStat = request.requestedDiffStat(),
            selectedDiffHunks = request.requestedSelectedDiffHunks(),
          ),
        )
      }
  }

  // SKILL-103 AC1: active_agent is sourced solely from persisted run state, never from the status
  // caller's resolution chain (--agent / SKILL_BILL_AGENT / detected / default). In order: the
  // current subtask's active workflow agent from the persisted phase ledger; else the subtask's
  // recorded finalizing/participating agent from the reconciled goal outcome; else null (omit).
  // The phase ledger is a runtime-mode concept, so a non-runtime child (e.g. a prose workflow) is
  // skipped rather than crashing the read — attribution then falls through to the subtask outcome.
  private fun resolveActiveAgent(currentSubtask: DecompositionSubtask?, dbPathOverride: String?): String? {
    if (currentSubtask == null) return null
    val workflowId = currentSubtask.workflowId?.takeIf(String::isNotBlank)
    if (workflowId != null &&
      phaseRecorder.existingWorkflowMode(workflowId, dbPathOverride) == FeatureTaskWorkflowMode.RUNTIME
    ) {
      agentAttributionFromPhaseState(phaseRecorder, workflowId, dbPathOverride).finalizingAgentId
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    }
    return currentSubtask.finalizingAgentId?.takeIf(String::isNotBlank)
      ?: currentSubtask.participatingAgentIds.firstOrNull()?.takeIf(String::isNotBlank)
  }

  fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  private fun GoalRunnerStatusRequest.requestedDiffStat() = if (includeDiffStat) {
    repoRoot
      ?.let(gitOperations::worktreeActivity)
      ?.takeIf { result -> result.ok }
      ?.diffStat
  } else {
    null
  }

  private fun GoalRunnerStatusRequest.requestedSelectedDiffHunks() = if (selectedDiffHunkPaths.isNotEmpty()) {
    repoRoot
      ?.let { root ->
        gitOperations.selectedDiffHunks(
          root,
          WorkflowSelectedDiffHunksRequest(
            paths = selectedDiffHunkPaths,
            maxHunks = selectedDiffMaxHunks,
            maxLines = selectedDiffMaxLines,
            maxBytes = selectedDiffMaxBytes,
          ),
        )
      }
      ?.takeIf { result -> result.ok }
      ?.selectedDiffHunks
  } else {
    null
  }

  private fun persistReconciledManifestIfChanged(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  ): GoalRunnerManifestState {
    val initialReconciled = loadedState.manifest.reconciledWithTerminalOutcomes(request, authoritativeOutcomes)
    if (initialReconciled == loadedState.manifest) {
      return loadedState
    }
    val latestState = manifestStore.loadByIssueKey(
      issueKey = loadedState.manifest.issueKey,
      dbPathOverride = request.dbPathOverride,
      repoRoot = request.repoRoot,
    ) ?: loadedState
    val latestReconciled = latestState.manifest.reconciledWithTerminalOutcomes(request, authoritativeOutcomes)
    return if (latestReconciled != latestState.manifest) {
      manifestStore.save(latestState.copy(manifest = latestReconciled), request.dbPathOverride)
    } else {
      latestState
    }
  }

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? {
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return null
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = loaded.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(allowInactiveReconciliation = true),
      dbPathOverride = request.dbPathOverride,
    )
    val latest = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot) ?: loaded
    val before = latest.manifest.toResetSnapshot()
    val resetManifest = latest.manifest.resetManifest(request.hard)
    val resetState = latest.copy(manifest = resetManifest)
    val saved = if (request.hard) {
      manifestStore.saveHardReset(resetState, request.dbPathOverride)
    } else {
      manifestStore.save(resetState, request.dbPathOverride)
    }
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = if (request.hard) "hard" else "soft",
      parentWorkflowId = saved.parentWorkflowId,
      before = before,
      after = saved.manifest.toResetSnapshot(),
    )
  }

  private fun DecompositionManifest.reconciledWithTerminalOutcomes(
    request: GoalRunnerStatusRequest,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  ): DecompositionManifest {
    val reconciledSubtasks = subtasks.map { subtask ->
      reconciledSubtask(subtask, request, authoritativeOutcomes)
    }
    return copy(subtasks = reconciledSubtasks)
      .withParentStatus()
      .withDerivedCurrentIntent()
  }

  private fun DecompositionManifest.reconciledSubtask(
    subtask: DecompositionSubtask,
    request: GoalRunnerStatusRequest,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  ): DecompositionSubtask {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val outcome = workflowId?.let { id ->
      preferredTerminalOutcome(subtask, id, request, authoritativeOutcomes)
    }
    return if (outcome == null || shouldPreserveCompletedSubtask(subtask, outcome)) {
      subtask
    } else {
      val status = outcome.toManifestStatus()
      subtask.copy(
        status = status,
        workflowId = outcome.workflowId.takeIf(String::isNotBlank) ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = outcome.blockedReason
          ?.takeIf { status == "blocked" }
          ?: subtask.blockedReason.takeIf { status == "blocked" },
        lastResumableStep = outcome.lastResumableStep ?: subtask.lastResumableStep,
      )
    }
  }

  private fun DecompositionManifest.preferredTerminalOutcome(
    subtask: DecompositionSubtask,
    workflowId: String,
    request: GoalRunnerStatusRequest,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  ): GoalRunnerStoredOutcome? = authoritativeOutcomes[subtask.id]
    ?.takeIf { outcome -> canApplyAuthoritativeOutcome(subtask, workflowId, outcome) }
    ?: outcomeStore.terminalOutcome(
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtask.id,
      dbPathOverride = request.dbPathOverride,
    )
}

private fun canApplyAuthoritativeOutcome(
  subtask: DecompositionSubtask,
  workflowId: String,
  outcome: GoalRunnerStoredOutcome,
): Boolean {
  val resetPendingSubtask = subtask.status == "pending" && subtask.workflowId.isNullOrBlank()
  if (resetPendingSubtask && outcome.status != GoalRunnerTerminalStatus.COMPLETE) {
    return false
  }
  // Do not let non-complete sibling outcomes overwrite an active retry workflow.
  val nonCompleteSibling = outcome.workflowId != workflowId && outcome.status != GoalRunnerTerminalStatus.COMPLETE
  return subtask.status != "in_progress" || !nonCompleteSibling
}

private fun shouldPreserveCompletedSubtask(subtask: DecompositionSubtask, outcome: GoalRunnerStoredOutcome): Boolean =
  subtask.status == "complete" &&
    !subtask.commitSha.isNullOrBlank() &&
    outcome.status != GoalRunnerTerminalStatus.COMPLETE

private fun GoalRunnerStoredOutcome.toManifestStatus(): String = when (status) {
  GoalRunnerTerminalStatus.COMPLETE -> "complete"
  GoalRunnerTerminalStatus.BLOCKED,
  GoalRunnerTerminalStatus.FAILED,
  GoalRunnerTerminalStatus.TIMEOUT,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  -> "blocked"
}

private fun DecompositionManifest.activeWorkflowIds(): Set<String> = subtasks
  .asSequence()
  .filter { subtask -> subtask.status == "in_progress" || currentSubtaskIntent.subtaskId == subtask.id }
  .mapNotNull(DecompositionSubtask::workflowId)
  .map(String::trim)
  .filter(String::isNotBlank)
  .toSet()

private fun DecompositionManifest.withDerivedCurrentIntent(): DecompositionManifest {
  val nextIntent = subtasks.firstOrNull { it.status == "blocked" }?.let { blocked ->
    CurrentSubtaskIntent(subtaskId = blocked.id, action = "blocked")
  } ?: subtasks.firstOrNull { it.status == "in_progress" }?.let { inProgress ->
    CurrentSubtaskIntent(subtaskId = inProgress.id, action = "resume")
  } ?: firstRunnablePendingSubtask()?.let { pending ->
    CurrentSubtaskIntent(subtaskId = pending.id, action = "start")
  } ?: CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  return copy(currentSubtaskIntent = nextIntent)
}

private fun DecompositionManifest.firstRunnablePendingSubtask(): DecompositionSubtask? {
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  return subtasks.firstOrNull { subtask ->
    subtask.status == "pending" && subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      dependencySubtask?.status in setOf("complete", "skipped") || (dependency.optional && dependency.skipped)
    }
  } ?: subtasks.firstOrNull { it.status == "pending" }
}

private fun DecompositionManifest.resetManifest(hard: Boolean): DecompositionManifest {
  val resetSubtasks = subtasks.map { subtask ->
    val preserveOutcome = !hard && subtask.status in setOf("complete", "skipped")
    if (preserveOutcome) {
      subtask.copy(
        blockedReason = null,
        lastResumableStep = null,
      )
    } else {
      subtask.copy(
        status = "pending",
        branch = null,
        commitSha = null,
        workflowId = null,
        blockedReason = null,
        lastResumableStep = null,
      )
    }
  }
  return copy(
    currentSubtaskIntent = restartIntent(resetSubtasks),
    subtasks = resetSubtasks,
  ).withParentStatus()
}

private fun restartIntent(subtasks: List<DecompositionSubtask>): CurrentSubtaskIntent {
  if (subtasks.all { it.status in setOf("complete", "skipped") }) {
    return CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  }
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  val nextRunnable = subtasks.firstOrNull { subtask ->
    subtask.status == "pending" && subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      dependencySubtask?.status in setOf("complete", "skipped") || (dependency.optional && dependency.skipped)
    }
  } ?: subtasks.firstOrNull { it.status == "pending" }
  return CurrentSubtaskIntent(
    subtaskId = nextRunnable?.id ?: 0,
    action = if (nextRunnable == null) "complete" else "start",
  )
}

private fun DecompositionManifest.toResetSnapshot(): GoalRunnerResetSnapshot = GoalRunnerResetSnapshot(
  status = status,
  currentSubtaskId = currentSubtaskIntent.subtaskId.takeIf { it > 0 },
  currentAction = currentSubtaskIntent.action,
  subtasks = subtasks.map { subtask ->
    GoalRunnerResetSubtaskSnapshot(
      id = subtask.id,
      status = subtask.status,
      branch = subtask.branch,
      workflowId = subtask.workflowId,
      commitSha = subtask.commitSha,
      blockedReason = subtask.blockedReason,
      lastResumableStep = subtask.lastResumableStep,
    )
  },
)

private fun skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent.toStatusMap(): Map<String, Any?> =
  linkedMapOf(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "workflow_phase" to workflowPhase,
    "worker_role" to workerRole,
    "liveness_class" to livenessClass,
    "activity_summary" to activitySummary,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
  )
