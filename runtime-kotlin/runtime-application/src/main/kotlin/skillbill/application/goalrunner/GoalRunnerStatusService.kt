package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.agentAttributionFromPhaseState
import skillbill.application.model.GoalRunnerAcceptRequest
import skillbill.application.model.GoalRunnerAcceptResult
import skillbill.application.model.GoalRunnerAcceptanceEvidence
import skillbill.application.model.GoalRunnerChildRecoveryDiagnostic
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerPauseResult
import skillbill.application.model.GoalRunnerResetSnapshot
import skillbill.application.model.GoalRunnerResetSubtaskSnapshot
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
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

@Inject
@Suppress("TooManyFunctions") // single cohesive boundary: status projection, reset, accept, and reconciliation
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val attemptLedgerStore: GoalRunnerAttemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.readByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.let { loadedState ->
        val authoritativeOutcomes = outcomeStore.authoritativeOutcomes(
          issueKey = loadedState.manifest.issueKey,
          dbPathOverride = request.dbPathOverride,
        )
        val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId, request.dbPathOverride)
        val manifest = loadedState.manifest.reconciledWithTerminalOutcomes(
          request.dbPathOverride,
          authoritativeOutcomes,
          acceptances,
        )
        val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
          subtask.id == manifest.currentSubtaskIntent.subtaskId
        }
        val progress = currentSubtask
          ?.workflowId
          ?.takeIf(String::isNotBlank)
          ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) }
        val planningBlock = currentSubtask?.takeIf { subtask ->
          subtask.status == "blocked" && subtask.lastResumableStep in setOf("preplan", "plan")
        }
        val ledgerSummary = runCatching {
          attemptLedgerStore.readAttemptLedgerSummary(loadedState.manifest.issueKey, request.dbPathOverride)
        }.getOrNull()
        GoalRunnerStatusProjector.project(
          manifest = manifest,
          activeAgent = resolveActiveAgent(currentSubtask, request.dbPathOverride),
          extras = GoalRunnerStatusProjectionExtras(
            executionLiveness = resolveExecutionLiveness(currentSubtask, request.dbPathOverride),
            planning = manifestStore.planningStatus(
              loadedState.parentWorkflowId,
              manifest.subtasks.filter { it.status != "skipped" }.map { it.id },
              planningBlock?.id,
              planningBlock?.blockedReason,
              request.dbPathOverride,
            ),
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
            stopAfterSubtaskId = loadedState.controlState.stopAfterSubtaskId,
          ),
        )
      }
  }

  /** Write the operator pause boundary directly; this does not inspect status, logs, files, or child state. */
  fun pause(issueKey: String, dbPathOverride: String?): GoalRunnerPauseResult {
    val persisted = manifestStore.requestPauseByIssueKey(issueKey, dbPathOverride)
      ?: return GoalRunnerPauseResult(issueKey = issueKey, status = "not_found")
    val control = persisted.controlState
    return GoalRunnerPauseResult(
      issueKey = issueKey,
      parentWorkflowId = persisted.parentWorkflowId,
      status = if (control.paused) "paused" else "requested",
      paused = control.paused,
      pauseRequested = control.pauseRequested,
      pauseReason = control.pauseReason,
    )
  }

  private fun resolveExecutionLiveness(
    currentSubtask: DecompositionSubtask?,
    dbPathOverride: String?,
  ): ExecutionLiveness {
    val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
      ?: return ExecutionLiveness.UNKNOWN
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
      manifestStore.loadDurableByIssueKey(request.issueKey, request.dbPathOverride)
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
    val before = latest.manifest.toResetSnapshot()
    val resetManifest = latest.manifest.resetManifest(request.hard)
    val resetState = latest.copy(manifest = resetManifest)
    val saved = if (request.hard) {
      manifestStore.saveHardReset(resetState, request.dbPathOverride, request.preservePlanning)
    } else {
      manifestStore.save(resetState, request.dbPathOverride)
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
    val reconciled = refreshed.manifest.reconciledWithTerminalOutcomes(
      request.dbPathOverride,
      outcomeStore.authoritativeOutcomes(refreshed.manifest.issueKey, request.dbPathOverride),
      manifestStore.outOfBandAcceptances(refreshed.parentWorkflowId, request.dbPathOverride),
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

  private fun DecompositionManifest.reconciledWithTerminalOutcomes(
    dbPathOverride: String?,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): DecompositionManifest {
    val reconciledSubtasks = subtasks.map { subtask ->
      reconciledSubtask(subtask, dbPathOverride, authoritativeOutcomes, acceptances)
    }
    return copy(subtasks = reconciledSubtasks)
      .withParentStatus()
      .withDerivedCurrentIntent()
  }

  private fun DecompositionManifest.reconciledSubtask(
    subtask: DecompositionSubtask,
    dbPathOverride: String?,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): DecompositionSubtask {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val outcome = workflowId?.let { id ->
      preferredTerminalOutcome(subtask, id, dbPathOverride, authoritativeOutcomes)
    }
    // Runtime evidence wins: an acceptance only speaks for a subtask the runtime never carried to
    // completion itself, so it can never downgrade or overwrite a genuine COMPLETE outcome.
    acceptances[subtask.id]
      ?.takeIf { outcome?.status != GoalRunnerTerminalStatus.COMPLETE }
      ?.let { acceptance ->
        return subtask.copy(
          status = "complete",
          commitSha = acceptance.commitSha,
          blockedReason = null,
          lastResumableStep = null,
        )
      }
    val staleRetryOutcome = workflowId != null &&
      outcome?.workflowId == workflowId &&
      outcome.status != GoalRunnerTerminalStatus.COMPLETE &&
      outcomeStore.progress(workflowId, dbPathOverride)?.workflowStatus == "running"
    return if (staleRetryOutcome) {
      subtask.copy(status = "in_progress", blockedReason = null)
    } else if (outcome == null || shouldPreserveCompletedSubtask(subtask, outcome)) {
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
    dbPathOverride: String?,
    authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  ): GoalRunnerStoredOutcome? = authoritativeOutcomes[subtask.id]
    ?.takeIf { outcome -> canApplyAuthoritativeOutcome(subtask, workflowId, outcome) }
    ?: outcomeStore.terminalOutcome(
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtask.id,
      dbPathOverride = dbPathOverride,
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
  // A crash-reconciled row is resumable, not blocked: keep the subtask in_progress so resume continues.
  GoalRunnerTerminalStatus.RECONCILABLE -> "in_progress"
  // A paused child awaits the operator decision and stays resumable, so it is not blocked either.
  GoalRunnerTerminalStatus.PAUSED -> "in_progress"
  GoalRunnerTerminalStatus.BLOCKED,
  GoalRunnerTerminalStatus.FAILED,
  GoalRunnerTerminalStatus.TIMEOUT,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  -> "blocked"
}

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
