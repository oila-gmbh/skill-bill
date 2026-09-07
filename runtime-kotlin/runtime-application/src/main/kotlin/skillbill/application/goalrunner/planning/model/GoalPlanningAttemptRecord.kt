package skillbill.application.goalrunner.planning.model

import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

data class GoalPlanningAttemptRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val dbPathOverride: String?,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val outcome: GoalProgressOutcome,
  val eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
)
