package skillbill.application.continuation.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey

data class GoalContinuationCandidate(
  val parentWorkflowId: WorkflowId,
  val issueKey: IssueKey,
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val updatedAt: String?,
  val summary: String,
)
