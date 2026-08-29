package skillbill.ports.goalrunner.verification.model

data class GoalVerificationBoundaryDiscovery(
  val boundaryCatalog: List<GoalPlanningBoundaryHeading>,
  val boundaryCatalogTruncated: Boolean,
  val boundaryContextUnavailable: Boolean,
)
