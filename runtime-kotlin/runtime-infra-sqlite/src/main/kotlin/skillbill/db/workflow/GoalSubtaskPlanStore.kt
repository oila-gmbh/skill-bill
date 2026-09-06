package skillbill.db.workflow

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.goalrunner.GoalSubtaskPlanRepository
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.workflow.decomposition.model.SubtaskId

internal class GoalSubtaskPlanStore(
  private val statusProjection: GoalPlanningStatusProjectionSql,
  private val subtaskPlan: GoalSubtaskPlanSql,
) : GoalSubtaskPlanRepository {
  override fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot = translateSqlFailure(parentGoalWorkflowId, blockedSubtaskId ?: 0) {
    statusProjection.boundedStatus(
      parentGoalWorkflowId,
      orderedSubtaskIds,
      blockedSubtaskId,
      blockedReason,
    )
  }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId.value) {
      subtaskPlan.checkpointSubtaskPlan(checkpoint)
    }
  }

  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId.value) {
      subtaskPlan.replaceSubtaskPlan(checkpoint)
    }
  }

  override fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: SubtaskId): Int =
    translateSqlFailure(parentGoalWorkflowId, subtaskId.value) {
      subtaskPlan.deleteSubtaskPlan(parentGoalWorkflowId, subtaskId.value)
    }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: SubtaskId,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, subtaskId.value) {
    subtaskPlan.findSubtaskPlan(expectedIdentity, subtaskId.value, governedSubSpecPath)
  }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, 0) {
    subtaskPlan.listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors)
  }
}
