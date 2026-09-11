package skillbill.workflow.goal

object NoopGoalObservabilityEventValidator : GoalObservabilityEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) {
  }
}
