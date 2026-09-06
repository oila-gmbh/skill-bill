package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId

internal class WorkflowGoalRunnerManifestWriteOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestPersistenceCommands {
  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ) = ctx.database.read {
    it.goalPlanningPreparations.boundedStatus(
      parentWorkflowId,
      orderedSubtaskIds,
      blockedSubtaskId,
      blockedReason,
    )
  }
  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState {
    val saved = ctx.projectionPersistence.save(state)
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
  override fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState =
    ctx.projectionPersistence.save(state).state
  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
  ): GoalRunnerCompletionPersistenceResult = ctx.controls.saveCompletedSubtaskAtBoundary(state, subtaskId)
  override fun saveHardReset(state: GoalRunnerManifestState, preservePlanning: Boolean): GoalRunnerManifestState {
    val saved = ctx.database.transaction { unitOfWork ->
      if (!preservePlanning) unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
      unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(state.parentWorkflowId)
      val repositoryIdentity = unitOfWork.goalRunnerControls.controlState(state.parentWorkflowId).repositoryIdentity
      val projection = ctx.projectionPersistence.saveInTransaction(
        unitOfWork,
        state,
        clearOutOfBandAcceptances = true,
        mergeConcurrentProgress = false,
      )
      if (repositoryIdentity != null) {
        unitOfWork.goalRunnerControls.persistControlState(
          projection.state.parentWorkflowId,
          projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
        )
      }
      projection.copy(
        state = projection.state.copy(
          controlState = projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
        ),
      )
    }
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    workflowId: WorkflowId,
  ): GoalRunnerManifestState {
    val saved = ctx.database.transaction { unitOfWork ->
      val selected = state.manifest.subtasks.singleOrNull { it.id == subtaskId.value }
        ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
      require(selected.workflowId == workflowId.value) {
        "Selected subtask '$subtaskId' does not own child workflow '$workflowId'."
      }
      val deleted = unitOfWork.workflowStates.deleteGoalChildWorkflow(
        state.parentWorkflowId,
        subtaskId,
        workflowId,
      )
      require(deleted == 1) {
        "Child workflow '$workflowId' is absent, compatible, or not owned by subtask '$subtaskId'."
      }
      val recoveredManifest = state.manifest.afterIncompatibleChildDeletion(subtaskId.value)
      ctx.projectionPersistence.saveInTransaction(unitOfWork, state.copy(manifest = recoveredManifest))
    }
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult {
    val saved = ctx.database.transaction { unitOfWork ->
      ctx.scopedReplanPersistence.executeScopedReplan(unitOfWork, state, subtaskId.value, options)
    }
    ctx.writeProjectionFile(state, saved.second)
    return saved.first
  }
  override fun sharedPreplanPayloadSha256(parentWorkflowId: String): String? = ctx.database.read {
    it.goalPlanningPreparations.sharedPreplanPayloadSha256(parentWorkflowId)
  }
  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState {
    val saved = ctx.database.transaction { unitOfWork ->
      ctx.childWorkflowPersistence.saveInTransaction(unitOfWork, state, setup)
    }
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
}
