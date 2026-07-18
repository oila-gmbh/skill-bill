package skillbill.ports.persistence

import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus

interface GoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: String): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

object EmptyGoalPlanningPreparationRepository : GoalPlanningPreparationRepository {
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
