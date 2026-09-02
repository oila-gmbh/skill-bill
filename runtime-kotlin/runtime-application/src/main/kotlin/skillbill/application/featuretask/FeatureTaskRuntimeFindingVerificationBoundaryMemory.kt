package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeFindingVerificationBoundaryMemory(
  val contextDiscovery: GoalPlanningContextDiscovery,
  val boundaryBodyResolver: GoalPlanningBoundaryBodyResolver,
) {
  fun sectionsForFindings(
    repoRoot: Path,
    requests: List<FeatureTaskRuntimeFindingBoundaryMemoryRequest>,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> = requests.map { request ->
    FeatureTaskRuntimeFindingBoundaryMemorySection(
      findingId = request.findingId,
      discovery = contextDiscovery.discoverForFindingPaths(
        repoRoot = repoRoot,
        findingPaths = request.findingPaths,
        loudFailOnCapExceeded = false,
      ),
    )
  }

  fun resolveSelectedBodies(
    repoRoot: Path,
    catalog: List<GoalPlanningBoundaryHeading>,
    selectedHeadingIds: List<String>,
  ): GoalPlanningResolvedBoundaryBodies = boundaryBodyResolver.resolve(
    repoRoot = repoRoot,
    headingIds = selectedHeadingIds,
    catalogHeadingIds = catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
    caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
    loudFailOnCapExceeded = true,
  )
}
