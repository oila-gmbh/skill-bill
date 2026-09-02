package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.goalrunner.persistence.planning.cascadeEligiblePlanSubtaskIds
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.workflow.decomposition.runtime.withParentStatus
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest

internal class WorkflowGoalRunnerScopedReplanPersistence(
  private val projectionPersistence: WorkflowGoalRunnerManifestProjectionPersistence,
) {
  fun executeScopedReplan(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): Pair<GoalRunnerScopedReplanWriteResult, String> {
    val preparations = unitOfWork.goalPlanningPreparations
    val plannedBefore = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedBefore = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    val discard = discardScopedReplanPlans(preparations, state, subtaskId, options, plannedBefore)
    val plannedAfter = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedAfter = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    val clearedChildIds = deleteStaleReplanChildren(
      unitOfWork,
      state,
      listOf(subtaskId) + discard.cascadedIds,
    )
    val projection = projectionPersistence.saveInTransaction(
      unitOfWork,
      state.copy(manifest = state.manifest.afterReplanChildDeletion(clearedChildIds)),
      mergeConcurrentProgress = false,
    )
    return GoalRunnerScopedReplanWriteResult(
      state = projection.state,
      deletedPlanCount = discard.deleted,
      plannedSubtaskIdsBefore = plannedBefore,
      plannedSubtaskIdsAfter = plannedAfter,
      sharedPreplanPrepared = sharedAfter,
      sharedPreplanPreparedBefore = sharedBefore,
      discardedSharedPreplan = sharedBefore && !sharedAfter,
      cascadedPlanSubtaskIds = discard.cascadedIds,
      clearedChildSubtaskIds = clearedChildIds,
    ) to projection.projectionArtifactsJson
  }

  private data class ScopedReplanDiscard(
    val cascadedIds: List<Int>,
    val deleted: Int,
  )

  private fun discardScopedReplanPlans(
    preparations: GoalPlanningPreparationRepository,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
    plannedBefore: List<Int>,
  ): ScopedReplanDiscard {
    if (!options.includeSharedPreplan) {
      return ScopedReplanDiscard(
        cascadedIds = emptyList(),
        deleted = preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId),
      )
    }
    val cascadedIds = cascadeEligiblePlanSubtaskIds(
      plannedIds = plannedBefore.filter { it != subtaskId },
      subtasks = state.manifest.subtasks,
    )
    val retainedIds = plannedBefore.filter { it != subtaskId && it !in cascadedIds }
    val expectedDigest = options.expectedSharedPayloadSha256
    val deleted = if (expectedDigest != null) {
      val identity = requireNotNull(options.planningIdentity) {
        "planningIdentity is required when discarding a shared preplan by digest."
      }
      if (retainedIds.isEmpty()) {
        preparations.deleteSharedPreplan(identity, expectedDigest)
        if (subtaskId in plannedBefore) 1 else 0
      } else {
        preparations.invalidateSharedPreplan(identity, expectedDigest)
        cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
        preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
      }
    } else {
      cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
      preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
    }
    return ScopedReplanDiscard(cascadedIds = cascadedIds, deleted = deleted)
  }
}

internal fun DecompositionManifest.afterIncompatibleChildDeletion(subtaskId: Int): DecompositionManifest = copy(
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "start"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id != subtaskId) {
      subtask
    } else {
      subtask.copy(
        status = "pending",
        branch = null,
        commitSha = null,
        workflowId = null,
        blockedReason = null,
        lastResumableStep = null,
      )
    }
  },
).withParentStatus()

internal fun deleteStaleReplanChildren(
  unitOfWork: UnitOfWork,
  state: GoalRunnerManifestState,
  subtaskIds: List<Int>,
): List<Int> = subtaskIds.distinct().sorted().filter { id ->
  val subtask = state.manifest.subtasks.singleOrNull { it.id == id }
  val childWorkflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
  if (subtask == null || childWorkflowId == null || subtask.status in setOf("complete", "skipped")) {
    false
  } else {
    unitOfWork.workflowStates.deleteGoalChildWorkflow(
      state.parentWorkflowId,
      id,
      childWorkflowId,
      GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE,
    ) == 1
  }
}

internal fun DecompositionManifest.afterReplanChildDeletion(subtaskIds: List<Int>): DecompositionManifest {
  if (subtaskIds.isEmpty()) return this
  return copy(
    subtasks = subtasks.map { subtask ->
      if (subtask.id !in subtaskIds) {
        subtask
      } else {
        subtask.copy(
          status = "pending",
          branch = null,
          commitSha = null,
          workflowId = null,
          blockedReason = null,
          lastResumableStep = null,
        )
      }
    },
  ).withParentStatus()
}
