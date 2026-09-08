package skillbill.application.goalrunner

import skillbill.application.featuretask.pruneResetSubtaskCheckpointRefs
import skillbill.application.goalrunner.model.GoalRunnerChildRecoveryDiagnostic
import skillbill.application.goalrunner.model.GoalRunnerReplanRequest
import skillbill.application.goalrunner.model.GoalRunnerReplanResult
import skillbill.application.goalrunner.model.GoalRunnerReplanSnapshot
import skillbill.application.goalrunner.model.GoalRunnerResetReplanCoordinatorDeps
import skillbill.application.goalrunner.model.GoalRunnerResetRequest
import skillbill.application.goalrunner.model.GoalRunnerResetResult
import skillbill.application.goalrunner.model.GoalRunnerResetSubtaskSnapshot
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

class GoalRunnerResetReplanCoordinator(deps: GoalRunnerResetReplanCoordinatorDeps) {
  private val manifestStore = deps.manifestStore
  private val outcomeStore = deps.outcomeStore
  private val gitOperations = deps.gitOperations
  private val diagnostics = deps.diagnostics
  private val projectionAssembler = deps.projectionAssembler
  private val repositoryRoot = deps.repositoryRoot
  private val repositoryEnclosingRootPort = deps.repositoryEnclosingRootPort
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
      GoalPlanningIdentity(
        parentGoalWorkflowId = loaded.parentWorkflowId,
        normalizedIssueKey = loaded.manifest.issueKey.trim().uppercase(),
        repositoryIdentity = goalRepositoryIdentity(
          request.repoRoot ?: repositoryRoot.path,
          repositoryEnclosingRootPort,
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

  fun hardResetPreflight(issueKey: String, dbPathOverride: String?): List<GoalRunnerAcceptedSubtask> {
    val state = manifestStore.loadDurableByIssueKey(issueKey, dbPathOverride) ?: return emptyList()
    return manifestStore.outOfBandAcceptances(state.parentWorkflowId, dbPathOverride).toAcceptedSubtasks()
  }

  private fun GoalRunnerResetRequest.takeHardResetRepositoryRoot(latest: GoalRunnerManifestState): Path? {
    if (!hard) return null
    val repoRoot = requireNotNull(repoRoot) {
      "A repository root is required for a hard reset so checkpoint refs are pruned from the correct repository."
    }
    manifestStore.bindRepositoryIdentity(
      latest.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
      dbPathOverride,
    )
    return repoRoot
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
    val liveness = projectionAssembler.resolveExecutionLiveness(
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

  private fun deleteIncompatibleChildWorkflow(
    request: GoalRunnerResetRequest,
    authoritativeState: GoalRunnerManifestState,
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
}
