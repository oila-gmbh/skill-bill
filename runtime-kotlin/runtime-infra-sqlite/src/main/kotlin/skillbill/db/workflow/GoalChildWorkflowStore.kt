package skillbill.db.workflow

import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import java.sql.Connection

internal class GoalChildWorkflowStore(
  private val connection: Connection,
) : GoalChildWorkflowStateRepository {
  override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int = connection.prepareStatement(
    """
      DELETE FROM feature_task_workflows
      WHERE workflow_id IN (
        SELECT workflows.workflow_id
        FROM feature_task_workflows AS workflows
        JOIN feature_task_execution_identities AS identities
          ON identities.workflow_id = workflows.workflow_id
        WHERE identities.route_scope = 'goal_child'
          AND json_extract(workflows.artifacts_json, '$.goal_continuation.parent_workflow_id') = ?
      )
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, parentWorkflowId)
    statement.executeUpdate()
  }

  override fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope,
  ): Int {
    val deletableStatuses = scope.deletableStatuses
    return connection.prepareStatement(
      """
        DELETE FROM feature_task_workflows
        WHERE workflow_id = ?
          AND workflow_status IN (${deletableStatuses.joinToString(", ") { "?" }})
          AND EXISTS (
            SELECT 1
            FROM feature_task_execution_identities AS identities
            WHERE identities.workflow_id = feature_task_workflows.workflow_id
              AND identities.route_scope = 'goal_child'
          )
          AND json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id') = ?
          AND json_extract(artifacts_json, '$.goal_continuation.subtask_id') = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      deletableStatuses.forEachIndexed { offset, status ->
        statement.setString(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + offset, status)
      }
      statement.setString(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + deletableStatuses.size, parentWorkflowId)
      statement.setInt(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + deletableStatuses.size + 1, subtaskId)
      statement.executeUpdate()
    }
  }
}
