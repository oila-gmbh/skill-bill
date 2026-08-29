package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

private object EmptyNormalizedGoalPlanningPreparationRepository : NormalizedGoalPlanningPreparationRepository {
  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) = Unit
  override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ) = Unit
  override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) = Unit
  override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = emptyList()
  override fun invalidateSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int = 0
  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null
  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int = 0
  override fun deleteSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int = 0
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
