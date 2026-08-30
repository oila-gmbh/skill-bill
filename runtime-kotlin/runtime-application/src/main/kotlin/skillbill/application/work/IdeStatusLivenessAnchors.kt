package skillbill.application.work

import skillbill.application.workflow.WorkflowFamily
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.db.UnitOfWork
import skillbill.ports.work.model.WorkItem
import java.time.Instant

internal class IdeStatusLivenessAnchors(
  private val unitOfWork: UnitOfWork,
  private val repositoryIdentity: String,
) {
  fun authoritativeUpdatedAt(item: WorkItem, family: IdeStatusWorkflowFamily): Instant? {
    val fromWorkflow = when (family) {
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        WorkflowFamily.VERIFY.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_GOAL -> null
    }?.let { parseInstantOrNull(it.updatedAt) }
      ?: latestGoalChildUpdatedAt(item, family)

    return listOfNotNull(fromWorkflow, item.stateEnteredAt, goalLeaseHeartbeatAt(item, family))
      .maxOrNull()
  }

  private fun goalLeaseHeartbeatAt(item: WorkItem, family: IdeStatusWorkflowFamily): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val lease = unitOfWork.goalRunnerControls.controlState(item.workflowId).executionLease ?: return null
    return parseInstantOrNull(lease.heartbeatAt)
  }

  private fun latestGoalChildUpdatedAt(item: WorkItem, family: IdeStatusWorkflowFamily): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val issueKey = item.issueKey?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    return unitOfWork.workflowStates
      .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
      .mapNotNull { parseInstantOrNull(it.workflow.updatedAt) }
      .maxOrNull()
  }
}
