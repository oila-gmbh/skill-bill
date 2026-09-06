package skillbill.application.goalrunner.planning.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

data class GoalPlanningAttemptRecord(
  val parentWorkflowId: WorkflowId,
  val issueKey: IssueKey,
  val phaseId: String,
  val subtaskId: SubtaskId,
  val attempt: Int,
  val outcome: GoalProgressOutcome,
  val eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
)
