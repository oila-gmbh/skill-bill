package skillbill.infrastructure.sqlite.goalrunner
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.acquireExecutionLease
import skillbill.ports.goalrunner.executionLease
import skillbill.ports.goalrunner.heartbeatExecutionLease
import skillbill.ports.goalrunner.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.goalrunner.persistence.pauseAtOperatorBoundary
import skillbill.ports.goalrunner.releaseExecutionLease
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.persistence.decompositionRuntime
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.decomposition.DecompositionManifestValidator
import java.time.Clock

internal class GoalRunnerControlCoordinator(
  internal val database: DatabaseSessionFactory,
  internal val decompositionManifestValidator: DecompositionManifestValidator,
  internal val clock: Clock,
  internal val saveProjection: (UnitOfWork, GoalRunnerManifestState) -> SavedManifestProjection,
) {
  fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    database.read(dbPathOverride) { unitOfWork -> unitOfWork.goalRunnerControls.controlState(parentWorkflowId) }

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String?,
  ): GoalRunnerControlState = database.transaction(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.persistControlState(parentWorkflowId, state)
  }

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

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    reconcileControlStateForManifest(unitOfWork, parentWorkflowId, decompositionManifestValidator)
    unitOfWork.goalRunnerControls.heartbeatExecutionLease(parentWorkflowId, lease)
  }

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)
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
