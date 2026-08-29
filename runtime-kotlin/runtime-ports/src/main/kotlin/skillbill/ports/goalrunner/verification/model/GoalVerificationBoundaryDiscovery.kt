package skillbill.ports.goalrunner.verification.model

import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading

data class GoalVerificationBoundaryDiscovery(
  val boundaryCatalog: List<GoalPlanningBoundaryHeading>,
  val boundaryCatalogTruncated: Boolean,
  val boundaryContextUnavailable: Boolean,
)
