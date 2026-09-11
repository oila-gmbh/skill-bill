package skillbill.workflow.goal

object NoopGoalProgressEventValidator : GoalProgressEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) {
  }
}
