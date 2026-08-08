package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

/**
 * Re-reads only the files owning the selected heading ids and returns those bodies. A heading id is
 * honoured only when re-parsing the current file still produces that exact id, so a stale selection
 * resolves to nothing rather than to whatever entry now occupies its ordinal.
 */
@Inject
class FileSystemGoalPlanningBoundaryBodyResolver : GoalPlanningBoundaryBodyResolver {
  override fun resolve(repoRoot: Path, headingIds: List<String>): GoalPlanningResolvedBoundaryBodies {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val bodies = mutableListOf<GoalPlanningBoundaryBody>()
    val unresolved = mutableListOf<String>()
    val parsedByPath = mutableMapOf<String, Map<String, BoundaryMemoryEntry>>()
    var truncated = false
    var totalBytes = 0
    for (headingId in headingIds.distinct()) {
      if (bodies.size >= GoalPlanningContext.MAX_SELECTED_BODIES ||
        totalBytes >= GoalPlanningContext.MAX_TOTAL_BODY_BYTES
      ) {
        truncated = true
        break
      }
      val sourcePath = BoundaryMemoryHeadingParser.sourcePathOf(headingId)
      val entry = sourcePath
        ?.let { path -> parsedByPath.getOrPut(path) { entriesOf(canonicalRoot, path) } }
        ?.get(headingId)
      if (entry == null) {
        unresolved.add(headingId)
        continue
      }
      val body = entry.body.take(GoalPlanningContext.MAX_BODY_BYTES)
      if (body.length < entry.body.length) truncated = true
      totalBytes += body.length
      bodies.add(GoalPlanningBoundaryBody(headingId, sourcePath, entry.heading, body))
    }
    return GoalPlanningResolvedBoundaryBodies(bodies, unresolved, truncated)
  }

  private fun entriesOf(repoRoot: Path, sourcePath: String): Map<String, BoundaryMemoryEntry> {
    val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, sourcePath) ?: return emptyMap()
    val content = GoalPlanningRepositoryScope.readTextOrNull(
      canonical,
      GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES,
    ) ?: return emptyMap()
    return BoundaryMemoryHeadingParser.parse(sourcePath, content).associateBy(BoundaryMemoryEntry::headingId)
  }
}
