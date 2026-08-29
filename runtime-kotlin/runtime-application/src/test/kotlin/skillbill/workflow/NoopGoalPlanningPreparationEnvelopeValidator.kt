package skillbill.workflow

import skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator

object NoopGoalPlanningPreparationEnvelopeValidator : GoalPlanningPreparationEnvelopeValidator {
  override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
}
