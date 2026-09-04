package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.persistence.decompositionRuntime
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal fun reconcileControlStateForManifest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
  validator: DecompositionManifestValidator,
) {
  val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId) ?: return
  val manifest = parent.decompositionRuntime(validator) ?: return
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  val reconciled = existing.reconciledForCurrentSubtask(manifest.currentSubtaskIntent.subtaskId)
  if (reconciled != existing) {
    unitOfWork.goalRunnerControls.persistControlState(parentWorkflowId, reconciled)
  }
}

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

internal fun GoalRunnerControlState.targetReached(state: GoalRunnerManifestState): Boolean =
  stopAfterSubtaskId?.let { targetId ->
    state.manifest.subtasks.any { it.id == targetId && it.status == "complete" }
  } == true && !stopAfterConsumed

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

internal fun GoalRunnerControlCoordinator.persistStopAfterSubtask(
  parentWorkflowId: String,
  subtaskId: Int,
  dbPathOverride: String?,
): GoalRunnerControlState = database.transaction(dbPathOverride) { unitOfWork ->
  require(subtaskId > 0) { "stop-after subtask id must be positive." }
  val parent = requireParent(unitOfWork, parentWorkflowId)
  val manifest = parent.decompositionRuntime(decompositionManifestValidator)
    ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
  require(manifest.subtasks.any { it.id == subtaskId }) {
    "Goal parent '$parentWorkflowId' has no subtask '$subtaskId'."
  }
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  require(existing.stopAfterSubtaskId == null || existing.stopAfterSubtaskId == subtaskId) {
    "Goal parent '$parentWorkflowId' already has stop-after subtask ${existing.stopAfterSubtaskId}."
  }
  existing.stopAfterSubtaskId?.let { existing }
    ?: unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(stopAfterSubtaskId = subtaskId),
    )
}

internal fun GoalRunnerControlCoordinator.resume(
  parentWorkflowId: String,
  dbPathOverride: String?,
): GoalRunnerManifestState? = database.transaction(dbPathOverride) { unitOfWork ->
  val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
    ?: return@transaction null
  migrateLegacyGoalRunnerControls(unitOfWork, parent)
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  val resumed = if (existing.paused || existing.pauseRequested) {
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = false,
        pauseConsumed = false,
        paused = false,
        pauseReason = null,
        pausedAt = null,
      ),
    )
  } else {
    existing
  }
  parent.decompositionRuntime(decompositionManifestValidator)?.let { manifest ->
    GoalRunnerManifestState(
      parentWorkflowId = parent.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      manifest = manifest,
      controlState = resumed,
    )
  }
}
