package skillbill.infrastructure.sqlite.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOrphanChildReplacementWrite
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.ports.workflow.persistence.toRecord
import skillbill.workflow.goal.model.GOAL_RECOVERY_AUDIT_ARTIFACT_KEY

internal class WorkflowGoalRunnerManifestWriteOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestWriteOps {
  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ) = ctx.database.read(dbPathOverride) {
    it.goalPlanningPreparations.boundedStatus(
      parentWorkflowId,
      orderedSubtaskIds,
      blockedSubtaskId,
      blockedReason,
    )
  }
  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    val saved = ctx.projectionPersistence.save(state, dbPathOverride)
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
  override fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    ctx.projectionPersistence.save(state, dbPathOverride).state
  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult =
    ctx.controls.saveCompletedSubtaskAtBoundary(state, subtaskId, dbPathOverride)
  override fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String?,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState {
    val saved = ctx.database.transaction(dbPathOverride) { unitOfWork ->
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
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    val saved = ctx.database.transaction(dbPathOverride) { unitOfWork ->
      val selected = state.manifest.subtasks.singleOrNull { it.id == subtaskId }
        ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
      require(selected.workflowId == workflowId) {
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
      val recoveredManifest = state.manifest.afterIncompatibleChildDeletion(subtaskId)
      ctx.projectionPersistence.saveInTransaction(unitOfWork, state.copy(manifest = recoveredManifest))
    }
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }
  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult {
    val saved = ctx.database.transaction(dbPathOverride) { unitOfWork ->
      ctx.scopedReplanPersistence.executeScopedReplan(unitOfWork, state, subtaskId, options)
    }
    ctx.writeProjectionFile(state, saved.second)
    return saved.first
  }
  override fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String?): String? =
    ctx.database.read(dbPathOverride) {
      it.goalPlanningPreparations.sharedPreplanPayloadSha256(parentWorkflowId)
    }
  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    val saved = ctx.database.transaction(dbPathOverride) { unitOfWork ->
      ctx.childWorkflowPersistence.saveInTransaction(unitOfWork, state, setup)
    }
    ctx.writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }

  override fun replaceOrphanChildWorkflow(write: GoalRunnerOrphanChildReplacementWrite): GoalRunnerManifestState {
    val saved = ctx.database.transaction(write.dbPathOverride) { unitOfWork ->
      val selected = write.state.manifest.subtasks.singleOrNull { it.id == write.subtaskId }
        ?: error("Unknown or ambiguous goal subtask '${write.subtaskId}'.")
      require(selected.workflowId == write.sourceWorkflowId) {
        "Selected subtask '${write.subtaskId}' does not own child workflow '${write.sourceWorkflowId}'."
      }
      unitOfWork.workflowStates.deleteGoalChildWorkflow(
        write.state.parentWorkflowId,
        write.subtaskId,
        write.sourceWorkflowId,
        GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE,
      )
      val childSaved = ctx.childWorkflowPersistence.saveInTransaction(unitOfWork, write.state, write.setup)
      val parentRecord = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, write.state.parentWorkflowId)
      if (parentRecord != null) {
        val artifacts = decodeArtifacts(parentRecord.artifactsJson).toMutableMap()
        val existing = (artifacts[GOAL_RECOVERY_AUDIT_ARTIFACT_KEY] as? List<*>)
          ?.filterIsInstance<Map<String, Any?>>()
          .orEmpty()
        artifacts[GOAL_RECOVERY_AUDIT_ARTIFACT_KEY] = existing + write.auditEntry.toArtifactMap()
        WorkflowFamily.TASK_RUNTIME.saveRecord(
          unitOfWork.workflowStates,
          parentRecord.toRecord().copy(artifactsJson = JsonSupport.mapToJsonString(artifacts)),
        )
      }
      childSaved
    }
    ctx.writeProjectionFile(write.state, saved.projectionArtifactsJson)
    return saved.state
  }
}
