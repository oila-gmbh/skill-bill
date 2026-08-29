package skillbill.db.workflow

import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalSubtaskPlanRepository
import skillbill.ports.goalrunner.LegacyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import java.sql.Connection

class GoalPlanningPreparationStore(
  connection: Connection,
) : GoalPlanningPreparationRepository,
  SharedGoalPreplanRepository by SharedGoalPreplanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSharedPreplanSql(connection),
  ),
  GoalSubtaskPlanRepository by GoalSubtaskPlanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSubtaskPlanSql(connection, GoalSharedPreplanSql(connection)),
  ),
  LegacyGoalPlanningPreparationRepository by LegacyGoalPlanningPreparationStore(
    GoalPlanningPreparationRecordSql(connection),
  ) {
  private val sharedPreplan = GoalSharedPreplanSql(connection)
  private val subtaskPlan = GoalSubtaskPlanSql(connection, sharedPreplan)
  private val preparationRecord = GoalPlanningPreparationRecordSql(connection)

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val plans = subtaskPlan.deleteAllByGoal(parentGoalWorkflowId)
    val shared = sharedPreplan.deleteAllByGoal(parentGoalWorkflowId)
    return plans + shared + preparationRecord.deletePreparedByGoal(parentGoalWorkflowId)
  }
}
