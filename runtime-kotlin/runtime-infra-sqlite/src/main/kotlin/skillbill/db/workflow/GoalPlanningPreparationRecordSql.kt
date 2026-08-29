package skillbill.db.workflow

import skillbill.db.core.inImmediateTransaction
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import java.sql.Connection

internal class GoalPlanningPreparationRecordSql(
  private val connection: Connection,
) {
  fun markPrepared(record: GoalPlanningPreparationRecord) {
    requirePreparedEnvelope(record)
    connection.inImmediateTransaction {
      if (connection.upsertPreparedRow(record)) return@inImmediateTransaction
      val stored = connection.selectStoredRecoveryIdentity(record.parentGoalWorkflowId, record.subtaskId)
        ?: return@inImmediateTransaction
      val reason = recoveryIdentityFailure(stored, record) ?: return@inImmediateTransaction
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = record.parentGoalWorkflowId,
        subtaskId = record.subtaskId,
        reason = reason,
      )
    }
  }

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
    connection.selectRecord(parentGoalWorkflowId, subtaskId)

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    connection.selectOrderedByGoal(parentGoalWorkflowId)

  fun preparedCount(parentGoalWorkflowId: String): Int = connection.countPrepared(parentGoalWorkflowId)

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? {
    if (orderedSubtaskIds.isEmpty()) return null
    val prepared = connection.preparedSubtaskStatuses(parentGoalWorkflowId)
    return orderedSubtaskIds.firstOrNull { id -> prepared[id] != GoalPlanningPreparationState.PREPARED.wireValue }
  }

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
    connection.selectStatus(parentGoalWorkflowId, subtaskId)

  fun deletePreparedByGoal(parentGoalWorkflowId: String): Int = connection.deletePreparedByGoal(parentGoalWorkflowId)
}
