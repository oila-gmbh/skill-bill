package skillbill.ports.persistence

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint

interface NormalizedGoalPlanningPreparationRepository {
  fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot = GoalPlanningStatusSnapshot(
    if (blockedReason == null) {
      skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
    } else {
      skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
    },
    false,
    0,
    orderedSubtaskIds.size,
    blockedSubtaskId ?: orderedSubtaskIds.firstOrNull(),
    blockedReason ?: "Goal planning has not started.",
  )
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): Unit =
    error("Shared goal preplan checkpointing is not implemented by this repository.")

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint?

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan checkpointing is not implemented by this repository.")

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint?

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint>

  fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).size

  fun firstMissingPlan(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int? {
    val prepared = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).mapTo(mutableSetOf()) { it.subtaskId }
    return orderedDescriptors.firstOrNull { it.subtaskId !in prepared }?.subtaskId
  }
}

interface LegacyGoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: String): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

interface GoalPlanningPreparationRepository :
  NormalizedGoalPlanningPreparationRepository,
  LegacyGoalPlanningPreparationRepository

private object EmptyNormalizedGoalPlanningPreparationRepository : NormalizedGoalPlanningPreparationRepository {
  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) = Unit
  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null
  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = null
  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> = emptyList()
}

private object EmptyLegacyGoalPlanningPreparationRepository : LegacyGoalPlanningPreparationRepository {
  override fun markPrepared(record: GoalPlanningPreparationRecord) = Unit

  override fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? = null

  override fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    emptyList()

  override fun preparedCount(parentGoalWorkflowId: String): Int = 0

  override fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? =
    orderedSubtaskIds.firstOrNull()

  override fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? = null

  override fun deleteByGoal(parentGoalWorkflowId: String): Int = 0
}

object EmptyGoalPlanningPreparationRepository :
  GoalPlanningPreparationRepository,
  NormalizedGoalPlanningPreparationRepository by EmptyNormalizedGoalPlanningPreparationRepository,
  LegacyGoalPlanningPreparationRepository by EmptyLegacyGoalPlanningPreparationRepository
