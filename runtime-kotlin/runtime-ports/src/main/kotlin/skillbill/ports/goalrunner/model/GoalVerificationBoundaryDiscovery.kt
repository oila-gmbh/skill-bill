package skillbill.ports.goalrunner.model

data class GoalVerificationBoundaryDiscovery(
  val boundaryCatalog: List<GoalPlanningBoundaryHeading>,
  val boundaryCatalogTruncated: Boolean,
  val boundaryContextUnavailable: Boolean,
)
