package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

/**
 * Resolves entry bodies for heading ids a planning agent selected out of the discovery catalog. Kept
 * separate from [GoalPlanningContextDiscovery] so that interface stays single-abstract-method.
 */
fun interface GoalPlanningBoundaryBodyResolver {
  fun resolve(repoRoot: Path, headingIds: List<String>): GoalPlanningResolvedBoundaryBodies

  companion object {
    val NONE: GoalPlanningBoundaryBodyResolver =
      GoalPlanningBoundaryBodyResolver { _, ids -> GoalPlanningResolvedBoundaryBodies(unresolvedHeadingIds = ids) }
  }
}
