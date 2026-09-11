package skillbill.engine.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.agentAttributionFromPhaseState
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.engine.goalrunner.planning.model.GoalPlanningStatusAlignRequestimport skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.io.IOException
import java.time.Instant

class GoalRunnerStatusProjectionAssembler(deps: GoalRunnerStatusProjectionAssemblerDeps) {
  val manifestStore = deps.manifestStore
  val outcomeStore = deps.outcomeStore
  val phaseRecorder = deps.phaseRecorder
  val gitOperations = deps.gitOperations
  val attemptLedgerStore = deps.attemptLedgerStore
  val clock = deps.clock
  val planningStatusReasonCoherence = deps.planningStatusReasonCoherence
  val diagnostics = deps.diagnostics
  val runtimeStatusService = deps.runtimeStatusService
  val repositoryRoot = deps.repositoryRoot
  fun project(loadedState: GoalRunnerManifestState, request: GoalRunnerStatusRequest): GoalRunnerStatusProjection {
    val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId)
    val manifest = reconcileStatusManifest(loadedState, request, acceptances)
    val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
      subtask.id == manifest.currentSubtaskIntent.subtaskId
    }
    return GoalRunnerStatusProjector.project(
      manifest = manifest,
      activeAgent = resolveActiveAgent(currentSubtask),
      extras = statusProjectionRuntimeInputs(        loadedState = loadedState,
        request = request,
        manifest = manifest,
        currentSubtask = currentSubtask,
        acceptances = acceptances,
      ),
    )
  }

  fun resolveExecutionLiveness(parentWorkflowId: String, currentSubtask: DecompositionSubtask?): ExecutionLiveness {
    val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
      ?: return resolveParentExecutionLiveness(parentWorkflowId)
    val childLiveness = resolveChildExecutionLiveness(workflowId)
    if (childLiveness == ExecutionLiveness.LIVE || childLiveness == ExecutionLiveness.UNKNOWN) {
      return childLiveness
    }
    return resolveParentExecutionLiveness(parentWorkflowId)
  }
}

internal fun GoalRunnerStatusProjectionAssembler.statusProjectionExtras(
  loadedState: GoalRunnerManifestState,
  request: GoalRunnerStatusRequest,
  manifest: DecompositionManifest,
  currentSubtask: DecompositionSubtask?,
  acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
): GoalRunnerStatusProjectionExtras {
  val childWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
  val progress = childWorkflowId?.let { workflowId ->
    outcomeStore.progress(workflowId)
  }
  val derivedCurrentStep = derivedChildCurrentStep(childWorkflowId)
  val ledgerSummary = runCatching {
    attemptLedgerStore.readAttemptLedgerSummary(loadedState.manifest.issueKey)
  }.getOrNull()
  return GoalRunnerStatusProjectionExtras(
    executionLiveness = resolveExecutionLiveness(
      parentWorkflowId = loadedState.parentWorkflowId,
      currentSubtask = currentSubtask,
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
  )?.let { snapshot ->
    planningStatusReasonCoherence.align(
      GoalPlanningStatusAlignRequest(
        snapshot = snapshot,
        parentWorkflowId = loadedState.parentWorkflowId,
        issueKey = manifest.issueKey,
        manifest = manifest,
        repoRoot = request.repoRoot ?: repositoryRoot.path,
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
    authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey),
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

internal fun GoalRunnerStatusProjectionAssembler.derivedChildCurrentStep(childWorkflowId: String?): String? {
  val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return null
  val statusService = runtimeStatusService ?: return null
  return try {
    statusService.status(
      FeatureTaskRuntimeStatusRequest(
        workflowId = workflowId,
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

internal fun GoalRunnerStatusProjectionAssembler.resolveChildExecutionLiveness(workflowId: String): ExecutionLiveness =
  runCatching {
    if (phaseRecorder.existingWorkflowMode(workflowId) != FeatureTaskWorkflowMode.RUNTIME) {
      ExecutionLiveness.UNKNOWN    } else {
      val ownership = phaseRecorder.workerOwnership(workflowId)
      if (ownership != null && Instant.parse(ownership.expiresAt).isAfter(clock.instant())) {
        livenessOfLeaseOwner(ownership)
      } else {
        ExecutionLiveness.IDLE
      }
    }
  }.getOrDefault(ExecutionLiveness.UNKNOWN)

internal fun GoalRunnerStatusProjectionAssembler.resolveParentExecutionLiveness(
  parentWorkflowId: String,
): ExecutionLiveness = runCatching {
  val lease = manifestStore.executionLease(parentWorkflowId)
    ?: return@runCatching ExecutionLiveness.IDLE
  if (Instant.parse(lease.expiresAt).isAfter(clock.instant())) {
    livenessOfLeaseOwner()
  } else {
    ExecutionLiveness.IDLE
  }
}.getOrDefault(ExecutionLiveness.UNKNOWN)

internal fun GoalRunnerStatusProjectionAssembler.livenessOfLeaseOwner(): ExecutionLiveness = ExecutionLiveness.LIVE

internal fun GoalRunnerStatusProjectionAssembler.resolveActiveAgent(currentSubtask: DecompositionSubtask?): String? {
  if (currentSubtask == null) return null
  val workflowId = currentSubtask.workflowId?.takeIf(String::isNotBlank)
  if (workflowId != null &&
    phaseRecorder.existingWorkflowMode(workflowId) == FeatureTaskWorkflowMode.RUNTIME
  ) {
    agentAttributionFromPhaseState(phaseRecorder, workflowId).finalizingAgentId
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
