package skillbill.application.model

import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome

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
