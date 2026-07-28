package skillbill.workflow

object NoopGoalPlanningPreparationEnvelopeValidator : GoalPlanningPreparationEnvelopeValidator {
  override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
}
