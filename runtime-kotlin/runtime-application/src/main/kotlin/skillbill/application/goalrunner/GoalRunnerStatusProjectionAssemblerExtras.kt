package skillbill.application.goalrunner

import skillbill.application.featuretask.agentAttributionFromPhaseState
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningStatusAlignRequest
import skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

internal fun GoalRunnerStatusProjectionAssembler.statusProjectionExtras(
  loadedState: GoalRunnerManifestState,
  request: GoalRunnerStatusRequest,
  manifest: DecompositionManifest,
  currentSubtask: DecompositionSubtask?,
  acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
): GoalRunnerStatusProjectionExtras {
  val childWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
  val progress = childWorkflowId?.let { workflowId ->
    outcomeStore.progress(workflowId, request.dbPathOverride)
  }
  val derivedCurrentStep = derivedChildCurrentStep(childWorkflowId, request.dbPathOverride)
  val ledgerSummary = runCatching {
    attemptLedgerStore.readAttemptLedgerSummary(loadedState.manifest.issueKey, request.dbPathOverride)
  }.getOrNull()
  return GoalRunnerStatusProjectionExtras(
    executionLiveness = resolveExecutionLiveness(
      parentWorkflowId = loadedState.parentWorkflowId,
      currentSubtask = currentSubtask,
      dbPathOverride = request.dbPathOverride,
    ),
    planning = alignedPlanningStatus(loadedState, request, manifest, currentSubtask),
    currentStepOverride = derivedCurrentStep ?: progress?.currentStepId,
    currentWorkflowStatus = progress?.workflowStatus,
    latestLivenessSignal = progress?.latestLivenessSignal,
    latestObservabilityEvent = progress?.latestGoalObservabilityEvent?.toStatusMap(),
    requestedDiffStat = requestedDiffStat(request),
    selectedDiffHunks = requestedSelectedDiffHunks(request),
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
    subtaskActiveDurationMs = loadedState.controlState.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf = loadedState.controlState.subtaskActiveDurationAsOf,
  )
}

internal fun GoalRunnerStatusProjectionAssembler.alignedPlanningStatus(
  loadedState: GoalRunnerManifestState,
  request: GoalRunnerStatusRequest,
  manifest: DecompositionManifest,
  currentSubtask: DecompositionSubtask?,
) = currentSubtask?.takeIf { subtask ->
  subtask.status == "blocked" && subtask.lastResumableStep in setOf("preplan", "plan")
}.let { planningBlock ->
  manifestStore.planningStatus(
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
  }
}

internal fun GoalRunnerStatusProjectionAssembler.reconcileStatusManifest(
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

internal fun GoalRunnerStatusProjectionAssembler.derivedChildCurrentStep(
  childWorkflowId: String?,
  dbPathOverride: String?,
): String? {
  val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return null
  val statusService = runtimeStatusService ?: return null
  return try {
    statusService.status(
      FeatureTaskRuntimeStatusRequest(
        workflowId = workflowId,
        dbPathOverride = dbPathOverride,
      ),
    )?.currentPhaseId?.takeIf(String::isNotBlank)
  } catch (error: ShellContentContractException) {
    diagnostics.warning(
      "Goal status omitted derived child phase for workflow '$workflowId': " +
        "the child's durable status could not be read.",
      error,
    )
    null
  } catch (error: IOException) {
    diagnostics.warning(
      "Goal status omitted derived child phase for workflow '$workflowId': " +
        "the child's durable status could not be read.",
      error,
    )
    null
  }
}

internal fun GoalRunnerStatusProjectionAssembler.resolveChildExecutionLiveness(
  workflowId: String,
  dbPathOverride: String?,
): ExecutionLiveness = runCatching {
  if (phaseRecorder.existingWorkflowMode(workflowId, dbPathOverride) != FeatureTaskWorkflowMode.RUNTIME) {
    ExecutionLiveness.UNKNOWN
  } else {
    val ownership = phaseRecorder.workerOwnership(workflowId, dbPathOverride)
    if (ownership != null && Instant.parse(ownership.expiresAt).isAfter(clock.instant())) {
      livenessOfLeaseOwner(ownership)
    } else {
      ExecutionLiveness.IDLE
    }
  }
}.getOrDefault(ExecutionLiveness.UNKNOWN)

internal fun GoalRunnerStatusProjectionAssembler.resolveParentExecutionLiveness(
  parentWorkflowId: String,
  dbPathOverride: String?,
): ExecutionLiveness = runCatching {
  val lease = manifestStore.executionLease(parentWorkflowId, dbPathOverride)
    ?: return@runCatching ExecutionLiveness.IDLE
  if (Instant.parse(lease.expiresAt).isAfter(clock.instant())) {
    livenessOfLeaseOwner(lease.asWorkerOwnership(parentWorkflowId))
  } else {
    ExecutionLiveness.IDLE
  }
}.getOrDefault(ExecutionLiveness.UNKNOWN)

internal fun GoalRunnerStatusProjectionAssembler.livenessOfLeaseOwner(
  ownership: FeatureTaskRuntimeWorkerOwnership,
): ExecutionLiveness = when (workerSupervisor.inspect(ownership)) {
  FeatureTaskRuntimeProcessInspection.NotRunning -> ExecutionLiveness.IDLE
  FeatureTaskRuntimeProcessInspection.ExactLive,
  is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
  is FeatureTaskRuntimeProcessInspection.Unsupported,
  -> ExecutionLiveness.LIVE
}

internal fun GoalRunnerStatusProjectionAssembler.resolveActiveAgent(
  currentSubtask: DecompositionSubtask?,
  dbPathOverride: String?,
): String? {
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

internal fun GoalRunnerStatusProjectionAssembler.requestedDiffStat(request: GoalRunnerStatusRequest) =
  if (request.includeDiffStat) {
    request.repoRoot
      ?.let(gitOperations::worktreeActivity)
      ?.takeIf { result -> result.ok }
      ?.diffStat
  } else {
    null
  }

internal fun GoalRunnerStatusProjectionAssembler.requestedSelectedDiffHunks(request: GoalRunnerStatusRequest) =
  if (request.selectedDiffHunkPaths.isNotEmpty()) {
    request.repoRoot
      ?.let { root ->
        gitOperations.selectedDiffHunks(
          root,
          WorkflowSelectedDiffHunksRequest(
            paths = request.selectedDiffHunkPaths,
            maxHunks = request.selectedDiffMaxHunks,
            maxLines = request.selectedDiffMaxLines,
            maxBytes = request.selectedDiffMaxBytes,
          ),
        )
      }
      ?.takeIf { result -> result.ok }
      ?.selectedDiffHunks
  } else {
    null
  }
