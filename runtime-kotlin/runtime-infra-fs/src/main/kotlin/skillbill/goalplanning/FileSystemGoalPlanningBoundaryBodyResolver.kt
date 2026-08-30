package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

@Inject
class FileSystemGoalPlanningBoundaryBodyResolver : GoalPlanningBoundaryBodyResolver {
  override fun resolve(
    repoRoot: Path,
    headingIds: List<String>,
    catalogHeadingIds: Set<String>,
    caps: GoalPlanningBoundaryBodyResolutionCaps,
    loudFailOnCapExceeded: Boolean,
  ): GoalPlanningResolvedBoundaryBodies {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val state = BoundaryBodyResolutionState(
      bodies = mutableListOf(),
      unresolved = mutableListOf(),
      parsedByPath = mutableMapOf(),
      truncated = false,
      totalBytes = 0,
    )
    val requested = headingIds.distinct()
    for ((index, headingId) in requested.withIndex()) {
      val step = resolveBoundaryHeading(
        BoundaryHeadingResolutionInput(
          canonicalRoot = canonicalRoot,
          headingId = headingId,
          index = index,
          requested = requested,
          catalogHeadingIds = catalogHeadingIds,
          caps = caps,
          loudFailOnCapExceeded = loudFailOnCapExceeded,
          state = state,
        ),
      )
      if (!step.continueLoop) {
        break
      }
    }
    return GoalPlanningResolvedBoundaryBodies(state.bodies, state.unresolved, state.truncated)
  }
}
