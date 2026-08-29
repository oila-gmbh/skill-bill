package skillbill.ports.goalrunner.planning

import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

/**
 * Resolves entry bodies for heading ids a planning agent selected out of the discovery catalog. Kept
 * separate from [GoalPlanningContextDiscovery] so that interface stays single-abstract-method.
 *
 * [catalogHeadingIds] is the closed set the catalog published. Heading ids are model-authored text,
 * so anything outside that set is reported unresolved rather than read: without it a selection can
 * name any conforming markdown file in the repository and pull its text into the plan prompt.
 */
interface GoalPlanningBoundaryBodyResolver {
  fun resolve(
    repoRoot: Path,
    headingIds: List<String>,
    catalogHeadingIds: Set<String>,
    caps: GoalPlanningBoundaryBodyResolutionCaps = GoalPlanningBoundaryBodyResolutionCaps.PLANNING,
    loudFailOnCapExceeded: Boolean = false,
  ): GoalPlanningResolvedBoundaryBodies

  companion object {
    val NONE: GoalPlanningBoundaryBodyResolver = object : GoalPlanningBoundaryBodyResolver {
      override fun resolve(
        repoRoot: Path,
        headingIds: List<String>,
        catalogHeadingIds: Set<String>,
        caps: GoalPlanningBoundaryBodyResolutionCaps,
        loudFailOnCapExceeded: Boolean,
      ) = GoalPlanningResolvedBoundaryBodies(unresolvedHeadingIds = headingIds)
    }
  }
}
