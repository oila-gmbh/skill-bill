package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

/**
 * Resolves entry bodies for heading ids a planning agent selected out of the discovery catalog. Kept
 * separate from [GoalPlanningContextDiscovery] so that interface stays single-abstract-method.
 *
 * [catalogHeadingIds] is the closed set the catalog published. Heading ids are model-authored text,
 * so anything outside that set is reported unresolved rather than read: without it a selection can
 * name any conforming markdown file in the repository and pull its text into the plan prompt.
 */
fun interface GoalPlanningBoundaryBodyResolver {
  fun resolve(
    repoRoot: Path,
    headingIds: List<String>,
    catalogHeadingIds: Set<String>,
  ): GoalPlanningResolvedBoundaryBodies

  companion object {
    val NONE: GoalPlanningBoundaryBodyResolver =
      GoalPlanningBoundaryBodyResolver { _, ids, _ -> GoalPlanningResolvedBoundaryBodies(unresolvedHeadingIds = ids) }
  }
}
