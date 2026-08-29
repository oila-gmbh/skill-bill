package skillbill.application.goalrunner

import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.toSnapshot
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path

internal fun GoalRunnerControlCoordinator.requireParent(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
): WorkflowStateSnapshot {
  val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
    ?: error("Unknown decomposed parent workflow '$parentWorkflowId'.")
  migrateLegacyGoalRunnerControls(unitOfWork, parent)
  return parent
}

internal fun GoalRunnerControlCoordinator.spawnAuthorization(
  state: GoalRunnerManifestState,
  dbPathOverride: String?,
): AgentRunSpawnAuthorization = object : AgentRunSpawnAuthorization {
  override fun <T> withAuthorization(spawn: () -> T): T = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = requireParent(unitOfWork, state.parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    val manifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
    if (controls.requiresPauseBoundary(manifest)) {
      throw GoalRunnerLaunchAuthorizationDeniedException(controls)
    }
    spawn()
  }
}

internal fun GoalRunnerControlCoordinator.persistPauseRequest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
): GoalRunnerControlState {
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  return if (existing.paused || existing.pauseRequested) {
    existing
  } else {
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = true,
        pauseConsumed = false,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_REQUEST,
      ),
    )
  }
}

internal fun GoalRunnerControlState.targetReached(state: GoalRunnerManifestState): Boolean =
  stopAfterSubtaskId?.let { targetId ->
    state.manifest.subtasks.any { it.id == targetId && it.status == "complete" }
  } == true && !stopAfterConsumed

internal fun GoalRunnerControlCoordinator.heartbeatExecutionLease(
  parentWorkflowId: String,
  lease: GoalRunnerExecutionLease,
  dbPathOverride: String?,
): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
  reconcileControlStateForManifest(unitOfWork, parentWorkflowId, decompositionManifestValidator)
  unitOfWork.goalRunnerControls.heartbeatExecutionLease(parentWorkflowId, lease)
}

internal fun GoalRunnerControlCoordinator.releaseExecutionLease(
  parentWorkflowId: String,
  ownerToken: String,
  generation: Long,
  dbPathOverride: String?,
): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
  unitOfWork.goalRunnerControls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)
}

internal fun GoalRunnerControlCoordinator.bindRepositoryIdentity(
  parentWorkflowId: String,
  repositoryIdentity: String,
  dbPathOverride: String?,
): GoalRunnerControlState = database.transaction(dbPathOverride) { unitOfWork ->
  require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
  val parent = requireParent(unitOfWork, parentWorkflowId)
  val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
  require(existing.repositoryIdentity == null || existing.repositoryIdentity == repositoryIdentity) {
    "Goal parent '$parentWorkflowId' belongs to another repository."
  }
  if (existing.repositoryIdentity == repositoryIdentity) {
    existing
  } else {
    unitOfWork.goalRunnerControls.persistControlState(
      parent.workflowId,
      existing.copy(repositoryIdentity = repositoryIdentity),
    )
  }
}

internal fun GoalRunnerControlCoordinator.requestPause(
  parentWorkflowId: String,
  dbPathOverride: String?,
): GoalRunnerControlState? = database.transaction(dbPathOverride) { unitOfWork ->
  WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)?.let { parent ->
    migrateLegacyGoalRunnerControls(unitOfWork, parent)
    persistPauseRequest(unitOfWork, parentWorkflowId)
  }
}

internal fun GoalRunnerControlCoordinator.requestPauseByIssueKey(
  issueKey: String,
  dbPathOverride: String?,
  repoRoot: Path?,
): GoalRunnerPausePersistenceResult? = database.transaction(dbPathOverride) { unitOfWork ->
  val parent = unitOfWork.workflowStates.findDecomposedParentWorkflow(
    issueKey,
    decompositionManifestValidator,
  ) ?: return@transaction null
  migrateLegacyGoalRunnerControls(unitOfWork, parent.toSnapshot())
  val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
  if (repoRoot != null) {
    val identity = goalRepositoryIdentity(repoRoot)
    require(existing.repositoryIdentity == null || existing.repositoryIdentity == identity) {
      "Goal parent '${parent.workflowId}' belongs to another repository."
    }
    if (existing.repositoryIdentity == null) {
      unitOfWork.goalRunnerControls.persistControlState(
        parent.workflowId,
        existing.copy(repositoryIdentity = identity),
      )
    }
  }
  GoalRunnerPausePersistenceResult(parent.workflowId, persistPauseRequest(unitOfWork, parent.workflowId))
}

internal fun GoalRunnerControlCoordinator.planningSpawnAuthorization(
  parentWorkflowId: String,
  dbPathOverride: String?,
): AgentRunSpawnAuthorization = object : AgentRunSpawnAuthorization {
  override fun <T> withAuthorization(spawn: () -> T): T = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = requireParent(unitOfWork, parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    val manifest = parent.decompositionRuntime(decompositionManifestValidator)
      ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
    if (controls.requiresPauseBoundary(manifest)) {
      throw GoalRunnerLaunchAuthorizationDeniedException(controls)
    }
    spawn()
  }
}
