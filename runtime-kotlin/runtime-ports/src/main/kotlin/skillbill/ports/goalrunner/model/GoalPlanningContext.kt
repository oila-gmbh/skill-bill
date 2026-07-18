package skillbill.ports.goalrunner.model

data class GoalPlanningContext(
  val platformPacks: Map<String, String>,
  val boundaryMemory: Map<String, String>,
  val validationGuidance: String,
)
