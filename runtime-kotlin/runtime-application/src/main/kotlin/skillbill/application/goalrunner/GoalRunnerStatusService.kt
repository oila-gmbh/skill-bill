package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.agentAttributionFromPhaseState
import skillbill.application.featuretask.pruneResetSubtaskCheckpointRefs
import skillbill.application.model.GoalPlanningStatusAlignRequest
import skillbill.application.model.GoalRunnerAcceptRequest
import skillbill.application.model.GoalRunnerAcceptResult
import skillbill.application.model.GoalRunnerAcceptanceEvidence
import skillbill.application.model.GoalRunnerAppliedRepair
import skillbill.application.model.GoalRunnerChildRecoveryDiagnostic
import skillbill.application.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.model.GoalRunnerPauseResult
import skillbill.application.model.GoalRunnerRepairRequest
import skillbill.application.model.GoalRunnerRepairResult
import skillbill.application.model.GoalRunnerRepairStatus
import skillbill.application.model.GoalRunnerReplanRequest
import skillbill.application.model.GoalRunnerReplanResult
import skillbill.application.model.GoalRunnerReplanSnapshot
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerResetSnapshot
import skillbill.application.model.GoalRunnerResetSubtaskSnapshot
import skillbill.application.model.GoalRunnerResumeResult
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.model.GoalRunnerStopStatus
import skillbill.application.model.GoalRunnerStopVerbResult
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val NO_CURRENT_SUBTASK_ID = 0
private const val NO_CURRENT_SUBTASK_ACTION = "none"
private const val SUBTASK_ACTION_START = "start"
private const val SUBTASK_ACTION_RESUME = "resume"
private const val SUBTASK_STATUS_IN_PROGRESS = "in_progress"
private const val SUBTASK_STATUS_PENDING = "pending"
private const val SUBTASK_STATUS_COMPLETE = "complete"
private const val SUBTASK_STATUS_SKIPPED = "skipped"

/** How long the stop verb lets the runner exit on its own before escalating to forcible termination. */
private const val GRACEFUL_TERMINATION_WAIT_MILLIS: Long = 5_000
private const val GRACEFUL_TERMINATION_POLL_MILLIS: Long = 250
private const val GRACEFUL_TERMINATION_POLLS: Int =
  (GRACEFUL_TERMINATION_WAIT_MILLIS / GRACEFUL_TERMINATION_POLL_MILLIS).toInt()

@Inject
// single cohesive boundary: status projection, reset, accept, and reconciliation, each with its own collaborator
@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val attemptLedgerStore: GoalRunnerAttemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
  private val clock: Clock = Clock.systemUTC(),
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  private val childRepairStore: GoalRunnerChildRepairStore = NoopGoalRunnerChildRepairStore,
  private val planningStatusReasonCoherence: GoalPlanningStatusReasonCoherence =
    GoalPlanningStatusReasonCoherence.NONE,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.readByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.let { loadedState ->
        val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId, request.dbPathOverride)
        val manifest = reconcileStatusManifest(loadedState, request, acceptances)
        val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
          subtask.id == manifest.currentSubtaskIntent.subtaskId
        }
        GoalRunnerStatusProjector.project(
          manifest = manifest,
          activeAgent = resolveActiveAgent(currentSubtask, request.dbPathOverride),
          extras = statusProjectionExtras(
            loadedState = loadedState,
            request = request,
            manifest = manifest,
            currentSubtask = currentSubtask,
            acceptances = acceptances,
          ),
        )
      }
  }

  private fun statusProjectionExtras(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    manifest: DecompositionManifest,
    currentSubtask: DecompositionSubtask?,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): GoalRunnerStatusProjectionExtras {
    val progress = currentSubtask
      ?.workflowId
      ?.takeIf(String::isNotBlank)
      ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) }
    val ledgerSummary = runCatching {
      attemptLedgerStore.readAttemptLedgerSummary(loadedState.manifest.issueKey, request.dbPathOverride)
    }.getOrNull()
    val planningBlock = currentSubtask?.takeIf { subtask ->
      subtask.status == "blocked" && subtask.lastResumableStep in setOf("preplan", "plan")
    }
    return GoalRunnerStatusProjectionExtras(
      executionLiveness = resolveExecutionLiveness(
        parentWorkflowId = loadedState.parentWorkflowId,
        currentSubtask = currentSubtask,
        dbPathOverride = request.dbPathOverride,
      ),
      planning = manifestStore.planningStatus(
        loadedState.parentWorkflowId,
        manifest.subtasks.filter { it.status != "skipped" }.map { it.id },
        planningBlock?.id,
        planningBlock?.blockedReason,
        request.dbPathOverride,
      )?.let { snapshot ->
        planningStatusReasonCoherence.align(
          GoalPlanningStatusAlignRequest(
            snapshot = snapshot,
            parentWorkflowId = loadedState.parentWorkflowId,
            issueKey = manifest.issueKey,
            manifest = manifest,
            repoRoot = request.repoRoot ?: Path.of("").toAbsolutePath().normalize(),
            dbPathOverride = request.dbPathOverride,
          ),
        )
      },
      currentStepOverride = progress?.currentStepId,
      currentWorkflowStatus = progress?.workflowStatus,
      latestLivenessSignal = progress?.latestLivenessSignal,
      latestObservabilityEvent = progress?.latestGoalObservabilityEvent?.toStatusMap(),
      requestedDiffStat = request.requestedDiffStat(),
      selectedDiffHunks = request.requestedSelectedDiffHunks(),
      blockedAttemptCount = ledgerSummary?.blockedAttemptCount ?: 0,
      supervisorKillCount = ledgerSummary?.supervisorKillCount ?: 0,
      phaseAttemptCounts = ledgerSummary?.phaseAttemptCounts ?: emptyMap(),
      cumulativeFixIterations = ledgerSummary?.cumulativeFixIterations ?: emptyMap(),
      reAttemptCauseCounts = ledgerSummary?.reAttemptCauseCounts ?: emptyMap(),
      findingsInScope = ledgerSummary?.findingsInScope,
      outOfBandAcceptances = acceptances.toAcceptedSubtasks(),
      paused = loadedState.controlState.paused,
      pauseRequested = loadedState.controlState.pauseRequested,
      pauseReason = loadedState.controlState.pauseReason,
      pausedAt = loadedState.controlState.pausedAt,
      stopAfterSubtaskId = loadedState.controlState.stopAfterSubtaskId,
      activeDurationMs = loadedState.controlState.activeDurationMs,
      activeDurationAsOf = loadedState.controlState.activeDurationAsOf,
    )
  }

  private fun reconcileStatusManifest(
    state: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): DecompositionManifest {
    val reconciled = reconcileGoalManifest(
      manifest = state.manifest,
      dbPathOverride = request.dbPathOverride,
      authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey, request.dbPathOverride),
      acceptances = acceptances,
      outcomeStore = outcomeStore,
    )
    request.repoRoot?.let { repoRoot ->
      pruneEligibleCheckpointRefsForManifest(
        manifest = reconciled,
        gitOperations = gitOperations,
        repoRoot = repoRoot,
        record = {},
      )
    }
    return reconciled
  }

  /** Write the operator pause boundary directly; this does not inspect status, logs, files, or child state. */
  fun pause(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerPauseResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerPauseResult(issueKey = issueKey, status = "not_found")
    val repositoryIdentity = goalRepositoryIdentity(repoRoot)
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, repositoryIdentity, dbPathOverride)
    val control = manifestStore.requestPause(loaded.parentWorkflowId, dbPathOverride)
      ?: return GoalRunnerPauseResult(issueKey = issueKey, status = "not_found")
    val effectiveControl = if (
      control.requiresPauseBoundary(loaded.manifest) && loaded.manifest.isAtUnlaunchedBoundary()
    ) {
      manifestStore.pauseAtBoundary(
        loaded.copy(controlState = control),
        dbPathOverride,
      ).controlState
    } else {
      control
    }
    return GoalRunnerPauseResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = if (effectiveControl.paused) "paused" else "requested",
      paused = effectiveControl.paused,
      pauseRequested = effectiveControl.pauseRequested,
      pauseReason = effectiveControl.pauseReason,
    )
  }

  /**
   * Stop a goal immediately: write durable operator intent first, then terminate the runner that
   * holds it. The write precedes every supervisor interaction and is never reordered — a stop whose
   * termination fails still leaves `paused = true`, so the surviving runner halts at its next
   * boundary anyway. Identity the supervisor cannot confirm is refused, never killed by pid.
   */
  fun stop(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerStopVerbResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, goalRepositoryIdentity(repoRoot), dbPathOverride)
    val alreadyStopped = loaded.controlState.paused &&
      loaded.controlState.pauseReason == GOAL_PAUSE_REASON_OPERATOR_STOP
    val control = manifestStore.pauseNow(
      parentWorkflowId = loaded.parentWorkflowId,
      reason = GOAL_PAUSE_REASON_OPERATOR_STOP,
      pausedAt = clock.instant().toString(),
      overwriteExistingReason = true,
      dbPathOverride = dbPathOverride,
    ) ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)

    fun outcome(status: GoalRunnerStopStatus, terminationAttempted: Boolean = false) = GoalRunnerStopVerbResult(
      issueKey = issueKey,
      status = status,
      parentWorkflowId = loaded.parentWorkflowId,
      pauseReason = control.pauseReason,
      pausedAt = control.pausedAt,
      terminationAttempted = terminationAttempted,
    )

    val lease = manifestStore.executionLease(loaded.parentWorkflowId, dbPathOverride)
    val noLiveLease = if (alreadyStopped) GoalRunnerStopStatus.ALREADY_STOPPED else GoalRunnerStopStatus.NO_LIVE_LEASE
    if (lease == null) return outcome(noLiveLease)
    val ownership = lease.asWorkerOwnership(loaded.parentWorkflowId)
    // The durable write already landed, so a throwing supervisor costs the operator nothing: the
    // stop is reported as attempted and the still-running goal halts at its next pause boundary.
    return runCatching {
      when (workerSupervisor.inspect(ownership)) {
        is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
        is FeatureTaskRuntimeProcessInspection.Unsupported,
        -> outcome(GoalRunnerStopStatus.IDENTITY_MISMATCH)
        FeatureTaskRuntimeProcessInspection.NotRunning -> outcome(noLiveLease)
        FeatureTaskRuntimeProcessInspection.ExactLive -> {
          terminateOwner(ownership)
          outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true)
        }
      }
    }.getOrElse { outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true) }
  }

  /** Graceful first, forcible only if the process outlives the wait, so the child agent can exit cleanly. */
  private fun terminateOwner(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerSupervisor.terminateGracefully(ownership)
    // Counted polls rather than a wall-clock deadline: the injected clock never advances on its own,
    // so a deadline loop would spin forever under a fixed test clock.
    repeat(GRACEFUL_TERMINATION_POLLS) {
      if (workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) return
      workerSupervisor.pause(GRACEFUL_TERMINATION_POLL_MILLIS)
    }
    if (workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      workerSupervisor.terminateForcibly(ownership)
    }
  }

  /**
   * Clear the operator pause boundary without starting child runs. A pause request that never
   * reached a boundary leaves `pauseRequested` set with `paused` false, so both fields clear here.
   */
  fun resume(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerResumeResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerResumeResult(issueKey = issueKey, status = "not_found")
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, goalRepositoryIdentity(repoRoot), dbPathOverride)
    val before = manifestStore.controlState(loaded.parentWorkflowId, dbPathOverride)
    if (!before.paused && !before.pauseRequested) {
      return GoalRunnerResumeResult(
        issueKey = issueKey,
        parentWorkflowId = loaded.parentWorkflowId,
        status = "not_paused",
      )
    }
    manifestStore.resume(loaded.parentWorkflowId, dbPathOverride)
      ?: return GoalRunnerResumeResult(issueKey = issueKey, status = "not_found")
    return GoalRunnerResumeResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = "resumed",
      clearedPauseReason = before.pauseReason,
    )
  }

  private fun DecompositionManifest.isAtUnlaunchedBoundary(): Boolean {
    val currentSubtask = subtasks.firstOrNull { it.id == currentSubtaskIntent.subtaskId }
    val activeChildExists = subtasks.any { subtask ->
      subtask.status == SUBTASK_STATUS_IN_PROGRESS && subtask.workflowId?.isNotBlank() == true
    }
    val unselected = currentSubtaskIntent.subtaskId == NO_CURRENT_SUBTASK_ID &&
      currentSubtaskIntent.action == NO_CURRENT_SUBTASK_ACTION
    val selectedButNotLaunched = currentSubtask?.let { subtask ->
      subtask.status == SUBTASK_STATUS_PENDING &&
        subtask.workflowId.isNullOrBlank() &&
        currentSubtaskIntent.action == SUBTASK_ACTION_START
    } == true
    return !activeChildExists && (unselected || selectedButNotLaunched)
  }

  private fun resolveExecutionLiveness(
    parentWorkflowId: String,
    currentSubtask: DecompositionSubtask?,
    dbPathOverride: String?,
  ): ExecutionLiveness {
    val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
      ?: return resolveParentExecutionLiveness(parentWorkflowId, dbPathOverride)
    return runCatching {
      if (phaseRecorder.existingWorkflowMode(workflowId, dbPathOverride) != FeatureTaskWorkflowMode.RUNTIME) {
        ExecutionLiveness.UNKNOWN
      } else {
        val ownership = phaseRecorder.workerOwnership(workflowId, dbPathOverride)
        if (ownership != null && Instant.parse(ownership.expiresAt).isAfter(clock.instant())) {
          ExecutionLiveness.LIVE
        } else {
          ExecutionLiveness.IDLE
        }
      }
    }.getOrDefault(ExecutionLiveness.UNKNOWN)
  }

  private fun resolveParentExecutionLiveness(parentWorkflowId: String, dbPathOverride: String?): ExecutionLiveness =
    runCatching {
      // A released or never-acquired parent lease means no goal runner holds the goal — idle.
      // UNKNOWN is reserved for lease-read failure (catch below), not for absence after clean exit.
      val lease = manifestStore.executionLease(parentWorkflowId, dbPathOverride)
        ?: return@runCatching ExecutionLiveness.IDLE
      if (Instant.parse(lease.expiresAt).isAfter(clock.instant())) {
        ExecutionLiveness.LIVE
      } else {
        ExecutionLiveness.IDLE
      }
    }.getOrDefault(ExecutionLiveness.UNKNOWN)

  private fun Map<Int, GoalRunnerOutOfBandAcceptance>.toAcceptedSubtasks(): List<GoalRunnerAcceptedSubtask> =
    values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId).map { acceptance ->
      GoalRunnerAcceptedSubtask(
        subtaskId = acceptance.subtaskId,
        commitSha = acceptance.commitSha,
        reason = acceptance.reason,
        acceptedAt = acceptance.acceptedAt,
      )
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

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? {
    val loaded = if (request.deleteChildWorkflow) {
      manifestStore.loadDurableByIssueKey(request.issueKey, request.dbPathOverride)?.copy(repoRoot = request.repoRoot)
    } else {
      manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
    }
      ?: return null
    if (request.deleteChildWorkflow) {
      return deleteIncompatibleChildWorkflow(request, loaded)
    }
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = loaded.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(allowInactiveReconciliation = true),
      dbPathOverride = request.dbPathOverride,
    )
    val latest = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot) ?: loaded
    val hardResetRepoRoot = request.takeHardResetRepositoryRoot(latest)
    val before = latest.manifest.toResetSnapshot()
    val resetManifest = latest.manifest.resetManifest(request.hard)
    val resetState = latest.copy(manifest = resetManifest)
    val saved = if (request.hard) {
      manifestStore.saveHardReset(resetState, request.dbPathOverride, request.preservePlanning)
    } else {
      manifestStore.save(resetState, request.dbPathOverride)
    }
    if (request.hard) {
      pruneResetSubtaskCheckpointRefs(
        gitOperations = gitOperations,
        repoRoot = requireNotNull(hardResetRepoRoot),
        issueKey = saved.manifest.issueKey,
        subtaskIds = before.subtasks.map { it.id },
        record = { message -> runCatching { diagnostics.warning(message) } },
      )
    }
    val staleChild = if (!request.hard) {
      currentChildRecoveryDiagnostic(saved.manifest, request.dbPathOverride)
    } else {
      null
    }
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = if (request.hard) "hard" else "soft",
      parentWorkflowId = saved.parentWorkflowId,
      before = before,
      after = saved.manifest.toResetSnapshot(),
      recovery = staleChild,
    )
  }

  private fun GoalRunnerResetRequest.takeHardResetRepositoryRoot(latest: GoalRunnerManifestState): Path? {
    if (!hard) return null
    val repoRoot = requireNotNull(repoRoot) {
      "A repository root is required for a hard reset so checkpoint refs are pruned from the correct repository."
    }
    manifestStore.bindRepositoryIdentity(
      latest.parentWorkflowId,
      goalRepositoryIdentity(repoRoot),
      dbPathOverride,
    )
    return repoRoot
  }

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? {
    val loaded = manifestStore.loadDurableByIssueKey(request.issueKey, request.dbPathOverride)
      ?: return null
    val selected = requireReplanTarget(loaded.manifest, request)
    requireIdleForScopedReplan(loaded, request)
    val beforeSubtasks = loaded.manifest.toResetSnapshot().subtasks
    val expectedSharedDigest = if (request.includeSharedPreplan) {
      manifestStore.sharedPreplanPayloadSha256(loaded.parentWorkflowId, request.dbPathOverride)
    } else {
      null
    }
    val planningIdentity = if (request.includeSharedPreplan && expectedSharedDigest != null) {
      skillbill.ports.persistence.model.GoalPlanningIdentity(
        parentGoalWorkflowId = loaded.parentWorkflowId,
        normalizedIssueKey = loaded.manifest.issueKey.trim().uppercase(),
        repositoryIdentity = goalRepositoryIdentity(
          request.repoRoot ?: Path.of("").toAbsolutePath().normalize(),
        ),
      )
    } else {
      null
    }
    val retargeted = loaded.copy(
      manifest = loaded.manifest.copy(currentSubtaskIntent = replanIntent(selected)),
      repoRoot = request.repoRoot,
    )
    val written = manifestStore.saveScopedReplan(
      state = retargeted,
      subtaskId = request.subtaskId,
      dbPathOverride = request.dbPathOverride,
      options = GoalRunnerScopedReplanOptions(
        includeSharedPreplan = request.includeSharedPreplan,
        expectedSharedPayloadSha256 = expectedSharedDigest,
        planningIdentity = planningIdentity,
      ),
    )
    return toReplanResult(request, loaded, written, beforeSubtasks)
  }

  private fun requireReplanTarget(
    manifest: DecompositionManifest,
    request: GoalRunnerReplanRequest,
  ): DecompositionSubtask {
    val selected = manifest.subtasks.singleOrNull { it.id == request.subtaskId }
    require(selected != null) {
      "Subtask '${request.subtaskId}' is not part of goal '${request.issueKey}'."
    }
    require(selected.status != SUBTASK_STATUS_COMPLETE && selected.status != SUBTASK_STATUS_SKIPPED) {
      "Subtask '${request.subtaskId}' is terminal (${selected.status}); use reset to reopen it before replanning."
    }
    return selected
  }

  private fun requireIdleForScopedReplan(loaded: GoalRunnerManifestState, request: GoalRunnerReplanRequest) {
    val currentSubtask = loaded.manifest.subtasks.firstOrNull { subtask ->
      subtask.id == loaded.manifest.currentSubtaskIntent.subtaskId
    }
    val liveness = resolveExecutionLiveness(
      parentWorkflowId = loaded.parentWorkflowId,
      currentSubtask = currentSubtask,
      dbPathOverride = request.dbPathOverride,
    )
    require(liveness == ExecutionLiveness.IDLE) {
      when (liveness) {
        ExecutionLiveness.LIVE ->
          "Goal '${request.issueKey}' is live; refuse scoped replan while a child or parent run is active."
        ExecutionLiveness.UNKNOWN ->
          "Goal '${request.issueKey}' has unknown execution liveness; refuse scoped replan."
        ExecutionLiveness.IDLE -> "Goal '${request.issueKey}' is idle."
      }
    }
  }

  private fun toReplanResult(
    request: GoalRunnerReplanRequest,
    before: GoalRunnerManifestState,
    written: GoalRunnerScopedReplanWriteResult,
    beforeSubtasks: List<GoalRunnerResetSubtaskSnapshot>,
  ): GoalRunnerReplanResult = GoalRunnerReplanResult(
    issueKey = written.state.manifest.issueKey,
    parentWorkflowId = written.state.parentWorkflowId,
    subtaskId = request.subtaskId,
    discardedPlan = written.deletedPlanCount > 0,
    discardedSharedPreplan = written.discardedSharedPreplan,
    cascadedPlanSubtaskIds = written.cascadedPlanSubtaskIds,
    clearedChildSubtaskIds = written.clearedChildSubtaskIds,
    before = GoalRunnerReplanSnapshot(
      status = before.manifest.status,
      currentSubtaskId = before.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
      currentAction = before.manifest.currentSubtaskIntent.action,
      sharedPreplanPrepared = written.sharedPreplanPreparedBefore,
      plannedSubtaskIds = written.plannedSubtaskIdsBefore,
      subtasks = beforeSubtasks,
    ),
    after = GoalRunnerReplanSnapshot(
      status = written.state.manifest.status,
      currentSubtaskId = written.state.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
      currentAction = written.state.manifest.currentSubtaskIntent.action,
      sharedPreplanPrepared = written.sharedPreplanPrepared,
      plannedSubtaskIds = written.plannedSubtaskIdsAfter,
      subtasks = written.state.manifest.toResetSnapshot().subtasks,
    ),
  )

  private fun currentChildRecoveryDiagnostic(
    manifest: DecompositionManifest,
    dbPathOverride: String?,
  ): GoalRunnerChildRecoveryDiagnostic? {
    val subtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId } ?: return null
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank) ?: return null
    val classification = classifyDurableChild(outcomeStore.progress(workflowId, dbPathOverride))
    return classification.takeIf { it == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL }?.let {
      GoalRunnerChildRecoveryDiagnostic(
        subtaskId = subtask.id,
        workflowId = workflowId,
        classification = it.wireValue,
        recoveryCommand = scopedChildRecoveryCommand(manifest.issueKey, subtask.id),
      )
    }
  }

  fun hardResetPreflight(issueKey: String, dbPathOverride: String?): List<GoalRunnerAcceptedSubtask> {
    val state = manifestStore.loadDurableByIssueKey(issueKey, dbPathOverride) ?: return emptyList()
    return manifestStore.outOfBandAcceptances(state.parentWorkflowId, dbPathOverride).toAcceptedSubtasks()
  }

  private fun deleteIncompatibleChildWorkflow(
    request: GoalRunnerResetRequest,
    authoritativeState: skillbill.ports.goalrunner.model.GoalRunnerManifestState,
  ): GoalRunnerResetResult {
    val subtaskId = requireNotNull(request.subtaskId)
    val selected = authoritativeState.manifest.subtasks.singleOrNull { it.id == subtaskId }
      ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
    require(selected.status == "blocked") {
      "Subtask '$subtaskId' is '${selected.status}'; scoped child deletion requires a blocked subtask."
    }
    val workflowId = selected.workflowId?.takeIf(String::isNotBlank)
      ?: error("Subtask '$subtaskId' has no durable child workflow to delete.")
    val classification = classifyDurableChild(outcomeStore.progress(workflowId, request.dbPathOverride))
    require(classification == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL) {
      "Child workflow '$workflowId' is ${classification.wireValue}; scoped deletion requires an incompatible " +
        "terminal child."
    }
    val saved = manifestStore.deleteIncompatibleChildWorkflow(
      authoritativeState,
      subtaskId,
      workflowId,
      request.dbPathOverride,
    )
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = "scoped_child_recovery",
      parentWorkflowId = saved.parentWorkflowId,
      before = authoritativeState.manifest.toResetSnapshot(),
      after = saved.manifest.toResetSnapshot(),
      recovery = GoalRunnerChildRecoveryDiagnostic(
        subtaskId = subtaskId,
        workflowId = workflowId,
        classification = classification.wireValue,
        recoveryCommand = null,
      ),
    )
  }

  /**
   * Inspect (default) or apply repairs for known goal-child wedge classes. Preserves completed
   * commit shas, review pass results, and audit repair state; mutates only the wedging field plus
   * durable repair evidence. Declines children whose worker lease is live.
   */
  // SKILL-176: inspect/apply paths are distinct guard exits
  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult {
    val repoRoot = request.repoRoot ?: Path.of("").toAbsolutePath().normalize()
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, repoRoot)
      ?: return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.NOT_FOUND,
      )
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot),
      request.dbPathOverride,
    )
    val children = loaded.manifest.subtasks
      .filter { request.subtaskId == null || it.id == request.subtaskId }
      .filter { !it.workflowId.isNullOrBlank() }
    if (request.subtaskId != null && children.isEmpty()) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.NOT_FOUND,
        parentWorkflowId = loaded.parentWorkflowId,
        refusalReason = "No child workflow is bound to subtask ${request.subtaskId}.",
      )
    }
    val diagnoses = children.map { subtask ->
      childRepairStore.diagnoseChildWedges(
        workflowId = requireNotNull(subtask.workflowId),
        issueKey = request.issueKey,
        subtaskId = subtask.id,
        subtasks = loaded.manifest.subtasks,
        repoRoot = repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
    }
    val wedged = diagnoses.filterNot(GoalRunnerChildWedgeDiagnosis::isHealthy)
    if (wedged.isEmpty()) {
      val status = if (request.apply && request.subtaskId != null) {
        GoalRunnerRepairStatus.NOT_WEDGED
      } else {
        GoalRunnerRepairStatus.HEALTHY
      }
      val healthy = diagnoses.firstOrNull { request.subtaskId == null || it.subtaskId == request.subtaskId }
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = status,
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
        refusalReason = when (status) {
          GoalRunnerRepairStatus.NOT_WEDGED ->
            "Subtask ${request.subtaskId} is not wedged; passed checks: " +
              (healthy?.passedChecks?.joinToString(", ") ?: "none")
          else -> "Goal children passed every repair check; no durable write."
        },
      )
    }
    val hardResetRequired = wedged.any { diagnosis ->
      diagnosis.wedges.any { it.wedgeClass.operatorRequired }
    }
    if (hardResetRequired) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = if (request.apply) {
          GoalRunnerRepairStatus.OPERATOR_REQUIRED
        } else {
          GoalRunnerRepairStatus.INSPECTED
        },
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
        refusalReason = "Phase-output contract version is incompatible with the installed runtime. " +
          "Recover with: '${goalPlanningHardResetRemedy(request.issueKey)}'.",
      )
    }
    if (!request.apply) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.INSPECTED,
        parentWorkflowId = loaded.parentWorkflowId,
        diagnoses = diagnoses,
      )
    }
    val applied = mutableListOf<GoalRunnerAppliedRepair>()
    for (diagnosis in wedged) {
      val workflowId = diagnosis.workflowId ?: continue
      val liveLease = childWorkerLeaseLive(workflowId, request.dbPathOverride)
      if (liveLease) {
        return GoalRunnerRepairResult(
          issueKey = request.issueKey,
          status = GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
          parentWorkflowId = loaded.parentWorkflowId,
          diagnoses = diagnoses,
          appliedRepairs = applied,
          liveLeaseWorkflowId = workflowId,
          refusalReason =
          "Child workflow '$workflowId' holds a live worker lease; a running worker owns that state.",
        )
      }
      val repairResult = childRepairStore.applyChildWedgeRepairs(
        workflowId = workflowId,
        issueKey = request.issueKey,
        subtaskId = diagnosis.subtaskId,
        wedgeClasses = diagnosis.wedges.map { it.wedgeClass },
        repoRoot = repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
      applied += repairResult.repairs
    }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = loaded.parentWorkflowId,
      diagnoses = diagnoses,
      appliedRepairs = applied,
    )
  }

  private fun childWorkerLeaseLive(workflowId: String, dbPathOverride: String?): Boolean {
    val ownership = runCatching { phaseRecorder.workerOwnership(workflowId, dbPathOverride) }.getOrNull()
      ?: return false
    return when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> true
      else -> false
    }
  }

  // The runtime cannot observe work an operator finished by hand after a child blocked. Without a
  // durable acceptance the goal keeps re-deriving that subtask as unstarted and proposes running it
  // again, so this is the supported alternative to hand-editing the manifest projection.
  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult {
    val loaded = if (request.restoreAfterHardReset) {
      manifestStore.loadDurableByIssueKey(request.issueKey, request.dbPathOverride)
    } else {
      manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
    }
      ?: return rejected(request, "No prepared goal exists for '${request.issueKey}'.")
    val repoRoot = request.repoRoot
      ?: return rejected(request, "A repository root is required to verify the accepted commit.")
    val resolvedSha = when (val evidence = acceptanceEvidence(request, loaded.manifest, repoRoot)) {
      is GoalRunnerAcceptanceEvidence.Rejected -> return rejected(request, evidence.reason)
      is GoalRunnerAcceptanceEvidence.Resolved -> evidence.commitSha
    }
    val acceptance = GoalRunnerOutOfBandAcceptance(
      subtaskId = request.subtaskId,
      commitSha = resolvedSha,
      reason = request.reason,
      acceptedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
    )
    manifestStore.persistOutOfBandAcceptance(loaded.parentWorkflowId, acceptance, request.dbPathOverride)
    val refreshed = if (request.restoreAfterHardReset) {
      manifestStore.loadDurableByIssueKey(request.issueKey, request.dbPathOverride)
    } else {
      manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, repoRoot)
    } ?: loaded
    val reconciled = reconcileGoalManifest(
      manifest = refreshed.manifest,
      dbPathOverride = request.dbPathOverride,
      authoritativeOutcomes = outcomeStore.authoritativeOutcomes(refreshed.manifest.issueKey, request.dbPathOverride),
      acceptances = manifestStore.outOfBandAcceptances(refreshed.parentWorkflowId, request.dbPathOverride),
      outcomeStore = outcomeStore,
    )
    val saved = manifestStore.save(refreshed.copy(manifest = reconciled), request.dbPathOverride)
    return GoalRunnerAcceptResult.Accepted(
      issueKey = saved.manifest.issueKey,
      parentWorkflowId = saved.parentWorkflowId,
      subtaskId = acceptance.subtaskId,
      commitSha = acceptance.commitSha,
      reason = acceptance.reason,
      acceptedAt = acceptance.acceptedAt,
      after = saved.manifest.toResetSnapshot(),
    )
  }

  private fun rejected(request: GoalRunnerAcceptRequest, reason: String): GoalRunnerAcceptResult.Rejected =
    GoalRunnerAcceptResult.Rejected(request.issueKey, reason)

  private fun acceptanceEvidence(
    request: GoalRunnerAcceptRequest,
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): GoalRunnerAcceptanceEvidence {
    val subtask = manifest.subtasks.firstOrNull { it.id == request.subtaskId }
      ?: return GoalRunnerAcceptanceEvidence.Rejected("Subtask ${request.subtaskId} is not part of this goal.")
    acceptanceStateRejection(request, subtask)?.let { reason ->
      return GoalRunnerAcceptanceEvidence.Rejected(reason)
    }
    val unsatisfiedDependencyId = unsatisfiedDependency(manifest, subtask)
    if (unsatisfiedDependencyId != null) {
      return GoalRunnerAcceptanceEvidence.Rejected(
        "Subtask ${request.subtaskId} depends on subtask $unsatisfiedDependencyId, which is not complete or skipped.",
      )
    }
    return resolvedAcceptanceEvidence(request, repoRoot)
  }

  private fun acceptanceStateRejection(request: GoalRunnerAcceptRequest, subtask: DecompositionSubtask): String? {
    val clearedByHardReset = subtask.status == "pending" &&
      subtask.branch == null &&
      subtask.commitSha == null &&
      subtask.workflowId == null &&
      subtask.blockedReason == null &&
      subtask.lastResumableStep == null
    return when {
      request.restoreAfterHardReset && !clearedByHardReset ->
        "Subtask ${request.subtaskId} is not in the cleared reset state required for acceptance restoration."
      !request.restoreAfterHardReset && clearedByHardReset ->
        "Subtask ${request.subtaskId} is in a cleared reset state; rerun the hard-reset restoration command."
      subtask.status == "complete" -> "Subtask ${request.subtaskId} is already complete."
      else -> null
    }
  }

  private fun resolvedAcceptanceEvidence(
    request: GoalRunnerAcceptRequest,
    repoRoot: Path,
  ): GoalRunnerAcceptanceEvidence {
    val resolved = gitOperations.resolveCommit(repoRoot, request.commitSha)
    val resolvedSha = resolved.value.trim()
    return if (resolved.ok && resolvedSha.isNotBlank()) {
      GoalRunnerAcceptanceEvidence.Resolved(resolvedSha)
    } else {
      GoalRunnerAcceptanceEvidence.Rejected(
        resolved.error.takeIf(String::isNotBlank)
          ?: "Commit '${request.commitSha}' could not be resolved in this repository.",
      )
    }
  }

  private fun unsatisfiedDependency(manifest: DecompositionManifest, subtask: DecompositionSubtask): Int? {
    val subtasksById = manifest.subtasks.associateBy(DecompositionSubtask::id)
    return subtask.dependencies.firstOrNull { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      val satisfied = dependencySubtask?.status in setOf("complete", "skipped") ||
        (dependency.optional && dependency.skipped)
      !satisfied
    }?.subtaskId
  }
}

private fun DecompositionManifest.resetManifest(hard: Boolean): DecompositionManifest {
  val freshReset: (DecompositionSubtask) -> DecompositionSubtask = { subtask ->
    subtask.copy(
      status = "pending",
      branch = null,
      commitSha = null,
      workflowId = null,
      blockedReason = null,
      lastResumableStep = null,
    )
  }
  val resetSubtasks = subtasks.map { subtask ->
    when {
      hard -> freshReset(subtask)
      subtask.status in setOf("complete", "skipped") -> subtask.copy(
        blockedReason = null,
        lastResumableStep = null,
      )
      !subtask.workflowId.isNullOrBlank() -> subtask.copy(
        status = "in_progress",
        blockedReason = null,
      )
      else -> freshReset(subtask)
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
  subtasks.firstOrNull { it.status == "in_progress" }?.let { resumable ->
    return CurrentSubtaskIntent(subtaskId = resumable.id, action = "resume")
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

private fun replanIntent(subtask: DecompositionSubtask): CurrentSubtaskIntent {
  val action = when {
    subtask.status == SUBTASK_STATUS_IN_PROGRESS || !subtask.workflowId.isNullOrBlank() -> SUBTASK_ACTION_RESUME
    else -> SUBTASK_ACTION_START
  }
  return CurrentSubtaskIntent(subtaskId = subtask.id, action = action)
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
