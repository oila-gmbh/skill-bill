package skillbill.ports.goalrunner.persistence.model

import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

data class BuildDeclaredGoalProgressEventArgs(
  val sourceLabel: String,
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val sequenceNumber: Int,
  val timestamp: String,
  val outcome: GoalProgressOutcome,
)
