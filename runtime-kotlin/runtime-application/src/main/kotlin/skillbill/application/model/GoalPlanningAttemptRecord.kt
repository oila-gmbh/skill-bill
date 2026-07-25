package skillbill.application.model

import skillbill.workflow.model.GoalProgressOutcome

data class GoalPlanningAttemptRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val dbPathOverride: String?,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val outcome: GoalProgressOutcome,
)
