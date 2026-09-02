package skillbill.application.goalrunner.planning.model

import skillbill.boundary.OpenBoundaryMap

data class GoalChildPlanningHydration(
  val currentStepId: String,
  @OpenBoundaryMap("Imported goal-planning workflow step updates at the child hydration seam")
  val stepUpdates: List<Map<String, Any?>>,
  @OpenBoundaryMap("Imported goal-planning durable workflow artifacts at the child hydration seam")
  val artifacts: Map<String, Any?>,
)
