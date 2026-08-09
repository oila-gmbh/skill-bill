package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

/**
 * Re-reads only the files owning the selected heading ids and returns those bodies. An id is honoured
 * only when the catalog published it, its path is governed boundary memory, and re-parsing the current
 * file still produces that exact id — so a stale, forged, or off-catalog selection resolves to nothing
 * rather than to whatever entry now occupies its place.
 */
@Inject
class FileSystemGoalPlanningBoundaryBodyResolver : GoalPlanningBoundaryBodyResolver {
  override fun resolve(
    repoRoot: Path,
    headingIds: List<String>,
    catalogHeadingIds: Set<String>,
  ): GoalPlanningResolvedBoundaryBodies {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val bodies = mutableListOf<GoalPlanningBoundaryBody>()
    val unresolved = mutableListOf<String>()
    val parsedByPath = mutableMapOf<String, Map<String, BoundaryMemoryEntry>>()
    var truncated = false
    var totalBytes = 0
    val requested = headingIds.distinct()
    for ((index, headingId) in requested.withIndex()) {
      if (bodies.size >= GoalPlanningContext.MAX_SELECTED_BODIES ||
        totalBytes >= GoalPlanningContext.MAX_TOTAL_BODY_BYTES
      ) {
        // Everything past the cap is still a selection that got no body. Reporting it keeps
        // unresolvedHeadingIds a complete account of what the plan phase did not receive.
        truncated = true
        unresolved.addAll(requested.subList(index, requested.size))
        break
      }
      val entry = entryFor(canonicalRoot, headingId, catalogHeadingIds, parsedByPath)
      if (entry == null) {
        unresolved.add(headingId)
      } else {
        val body = GoalPlanningRepositoryScope.truncateToUtf8Bytes(entry.body, GoalPlanningContext.MAX_BODY_BYTES)
        if (body.length < entry.body.length) truncated = true
        totalBytes += GoalPlanningRepositoryScope.utf8Size(body)
        bodies.add(
          GoalPlanningBoundaryBody(
            headingId = headingId,
            sourcePath = BoundaryMemoryHeadingParser.sourcePathOf(headingId).orEmpty(),
            heading = entry.heading,
            body = body,
          ),
        )
      }
    }
    return GoalPlanningResolvedBoundaryBodies(bodies, unresolved, truncated)
  }

  private fun entryFor(
    repoRoot: Path,
    headingId: String,
    catalogHeadingIds: Set<String>,
    parsedByPath: MutableMap<String, Map<String, BoundaryMemoryEntry>>,
  ): BoundaryMemoryEntry? {
    if (headingId !in catalogHeadingIds) return null
    val sourcePath = BoundaryMemoryHeadingParser.sourcePathOf(headingId) ?: return null
    if (!GoalPlanningRepositoryScope.isBoundaryMemoryPath(sourcePath)) return null
    return parsedByPath.getOrPut(sourcePath) { entriesOf(repoRoot, sourcePath) }[headingId]
  }

  private fun entriesOf(repoRoot: Path, sourcePath: String): Map<String, BoundaryMemoryEntry> {
    val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, sourcePath) ?: return emptyMap()
    val read = GoalPlanningRepositoryScope.readFileOrNull(canonical, GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES)
      ?: return emptyMap()
    return BoundaryMemoryHeadingParser.parse(sourcePath, read.text).associateBy(BoundaryMemoryEntry::headingId)
  }
}
