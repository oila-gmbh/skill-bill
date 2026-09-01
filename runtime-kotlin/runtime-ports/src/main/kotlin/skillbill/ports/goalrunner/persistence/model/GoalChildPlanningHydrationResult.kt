package skillbill.ports.goalrunner.persistence.model

data class GoalChildPlanningHydrationResult(
  val currentStepId: String,
  val stepUpdates: List<Map<String, Any?>>,
  val artifacts: Map<String, Any?>,
)
