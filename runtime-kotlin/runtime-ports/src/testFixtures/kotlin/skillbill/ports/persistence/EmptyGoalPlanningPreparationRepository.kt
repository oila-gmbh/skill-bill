package skillbill.ports.persistence

import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint

private object EmptyNormalizedGoalPlanningPreparationRepository : NormalizedGoalPlanningPreparationRepository {
  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) = Unit
  override fun replaceSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint, expectedPayloadSha256: String) = Unit
  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null
  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int = 0
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
