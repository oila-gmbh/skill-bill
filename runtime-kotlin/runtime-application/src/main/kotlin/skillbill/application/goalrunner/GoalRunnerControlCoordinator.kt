package skillbill.application.goalrunner

import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.DecompositionManifestValidator
import java.time.Clock

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
internal class GoalRunnerControlCoordinator(
  internal val database: DatabaseSessionFactory,
  internal val decompositionManifestValidator: DecompositionManifestValidator,
  internal val clock: Clock,
  internal val saveProjection: (UnitOfWork, GoalRunnerManifestState) -> SavedManifestProjection,
) {
  fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    database.read(dbPathOverride) { unitOfWork -> unitOfWork.goalRunnerControls.controlState(parentWorkflowId) }

  fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    database.read(dbPathOverride) { unitOfWork -> unitOfWork.goalRunnerControls.executionLease(parentWorkflowId) }

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    reconcileControlStateForManifest(unitOfWork, parentWorkflowId, decompositionManifestValidator)
    unitOfWork.goalRunnerControls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
  }

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization = database.transaction(dbPathOverride) { unitOfWork ->
    require(subtaskId > 0) { "subtaskId must be positive." }
    val existing = requireParent(unitOfWork, state.parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(existing.workflowId)
    val manifest = existing.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
    GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(manifest),
      controlState = controls,
      spawnAuthorization = spawnAuthorization(state, dbPathOverride),
    )
  }

  fun persistStopAfterSubtask(
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

  /**
   * One bounded transaction that flips the goal straight to paused. No status inspection, log read,
   * file read, or child supervision: the stop verb and the shutdown hook both call this on paths
   * where anything more is unsafe or unaffordable.
   */
  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState? = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: return@transaction null
    migrateLegacyGoalRunnerControls(unitOfWork, parent)
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    // Reason precedence: an already-paused goal keeps the more specific reason the stop verb wrote.
    if (existing.paused && !overwriteExistingReason) {
      return@transaction existing
    }
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = reason,
        pausedAt = pausedAt,
      ),
    )
  }

  fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState? =
    database.transaction(dbPathOverride) { unitOfWork ->
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

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    database.transaction(dbPathOverride) { unitOfWork ->
      val parent = requireParent(unitOfWork, state.parentWorkflowId)
      val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
      val authoritativeManifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
      val authoritativeState = state.copy(manifest = authoritativeManifest)
      val targetReached = controls.targetReached(authoritativeState)
      val pausedControls = if (controls.requiresPauseBoundary(authoritativeManifest)) {
        controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached)
      } else {
        controls
      }
      val saved = saveProjection(unitOfWork, authoritativeState)
      if (pausedControls != controls) {
        unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, pausedControls)
      }
      saved.state.copy(controlState = pausedControls)
    }

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = requireParent(unitOfWork, state.parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    val persistedManifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
    val authoritativeManifest = mergeConcurrentGoalProgress(persistedManifest, state.manifest)
    val authoritativeState = state.copy(manifest = authoritativeManifest)
    val targetReached = controls.stopAfterSubtaskId == subtaskId && !controls.stopAfterConsumed
    val operatorRequested = controls.pauseRequested && !controls.pauseConsumed
    val shouldPause = controls.requiresPauseBoundary(authoritativeManifest) || targetReached || operatorRequested
    val nextControls =
      if (shouldPause) controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached) else controls
    val saved = saveProjection(unitOfWork, authoritativeState)
    if (nextControls != controls) {
      unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, nextControls)
    }
    GoalRunnerCompletionPersistenceResult(
      state = saved.state.copy(controlState = nextControls),
      paused = shouldPause,
    )
  }
}
