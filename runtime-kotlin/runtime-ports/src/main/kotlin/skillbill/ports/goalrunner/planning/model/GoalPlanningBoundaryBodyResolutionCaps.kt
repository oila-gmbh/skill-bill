package skillbill.ports.goalrunner.planning.model

import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps

data class GoalPlanningBoundaryBodyResolutionCaps(
  val maxSelectedBodies: Int = GoalPlanningContext.MAX_SELECTED_BODIES,
  val maxBodyBytes: Int = GoalPlanningContext.MAX_BODY_BYTES,
  val maxTotalBodyBytes: Int = GoalPlanningContext.MAX_TOTAL_BODY_BYTES,
) {
  companion object {
    val PLANNING: GoalPlanningBoundaryBodyResolutionCaps = GoalPlanningBoundaryBodyResolutionCaps()
    val VERIFICATION: GoalPlanningBoundaryBodyResolutionCaps = GoalPlanningBoundaryBodyResolutionCaps(
      maxSelectedBodies = GoalVerificationBoundaryCaps.maxSelectedBodies,
      maxBodyBytes = GoalVerificationBoundaryCaps.maxBodyBytes,
      maxTotalBodyBytes = GoalVerificationBoundaryCaps.maxTotalBodyBytes,
    )
  }
}
