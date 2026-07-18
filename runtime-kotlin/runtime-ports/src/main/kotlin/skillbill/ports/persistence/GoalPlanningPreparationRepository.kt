package skillbill.ports.persistence

import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint

interface GoalPlanningPreparationRepository {
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): Unit =
    error("Shared goal preplan checkpointing is not implemented by this repository.")

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan checkpointing is not implemented by this repository.")

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = null

  fun listSubtaskPlansOrdered(expectedIdentity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint> = emptyList()

  fun preparedPlanCount(expectedIdentity: GoalPlanningIdentity): Int = listSubtaskPlansOrdered(expectedIdentity).size

  fun firstMissingPlan(expectedIdentity: GoalPlanningIdentity, orderedSubtaskIds: List<Int>): Int? {
    val prepared = listSubtaskPlansOrdered(expectedIdentity).mapTo(mutableSetOf()) { it.subtaskId }
    return orderedSubtaskIds.firstOrNull { it !in prepared }
  }

  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: String): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

object EmptyGoalPlanningPreparationRepository : GoalPlanningPreparationRepository {
  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) = Unit
  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null
  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit
  override fun findSubtaskPlan(expectedIdentity: GoalPlanningIdentity, subtaskId: Int, governedSubSpecPath: String): GoalSubtaskPlanCheckpoint? = null
  override fun listSubtaskPlansOrdered(expectedIdentity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint> = emptyList()
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
