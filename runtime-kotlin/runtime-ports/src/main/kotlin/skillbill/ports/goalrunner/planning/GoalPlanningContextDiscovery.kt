package skillbill.ports.goalrunner.planning

import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import java.nio.file.Path

interface GoalPlanningContextDiscovery {
  fun discover(repoRoot: Path): GoalPlanningContext

  fun discoverForFindingPaths(
    repoRoot: Path,
    findingPaths: List<String>,
    loudFailOnCapExceeded: Boolean = false,
  ): GoalVerificationBoundaryDiscovery

  companion object {
    private val EMPTY =
      GoalPlanningContext(boundaryCatalog = emptyList(), boundaryCatalogTruncated = false, validationGuidance = "")
    private val EMPTY_VERIFICATION = GoalVerificationBoundaryDiscovery(
      boundaryCatalog = emptyList(),
      boundaryCatalogTruncated = false,
      boundaryContextUnavailable = true,
    )

    val NONE: GoalPlanningContextDiscovery = object : GoalPlanningContextDiscovery {
      override fun discover(repoRoot: Path): GoalPlanningContext = EMPTY

      override fun discoverForFindingPaths(
        repoRoot: Path,
        findingPaths: List<String>,
        loudFailOnCapExceeded: Boolean,
      ): GoalVerificationBoundaryDiscovery = EMPTY_VERIFICATION
    }
  }
}
