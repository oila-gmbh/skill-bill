package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.error.GoalVerificationBoundaryCapExceededError
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

@Inject
@Suppress("LoopWithTooManyJumpStatements")
class FileSystemGoalPlanningBoundaryBodyResolver : GoalPlanningBoundaryBodyResolver {
  override fun resolve(
    repoRoot: Path,
    headingIds: List<String>,
    catalogHeadingIds: Set<String>,
    caps: GoalPlanningBoundaryBodyResolutionCaps,
    loudFailOnCapExceeded: Boolean,
  ): GoalPlanningResolvedBoundaryBodies {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val bodies = mutableListOf<GoalPlanningBoundaryBody>()
    val unresolved = mutableListOf<String>()
    val parsedByPath = mutableMapOf<String, Map<String, BoundaryMemoryEntry>>()
    var truncated = false
    var totalBytes = 0
    val requested = headingIds.distinct()
    for ((index, headingId) in requested.withIndex()) {
      if (bodies.size >= caps.maxSelectedBodies || totalBytes >= caps.maxTotalBodyBytes) {
        if (loudFailOnCapExceeded) {
          throw GoalVerificationBoundaryCapExceededError(
            "finding verification boundary body resolution exceeded max_selected_bodies or max_total_body_bytes",
          )
        }
        truncated = true
        unresolved.addAll(requested.subList(index, requested.size))
        break
      }
      val entry = entryFor(canonicalRoot, headingId, catalogHeadingIds, parsedByPath)
      if (entry == null) {
        unresolved.add(headingId)
      } else {
        val body = GoalPlanningRepositoryScope.truncateToUtf8Bytes(entry.body, caps.maxBodyBytes)
        val bodyBytes = GoalPlanningRepositoryScope.utf8Size(body)
        if (body.length < entry.body.length) {
          if (loudFailOnCapExceeded) {
            throw GoalVerificationBoundaryCapExceededError(
              "finding verification boundary body resolution exceeded max_body_bytes for heading '$headingId'",
            )
          }
          truncated = true
        }
        if (totalBytes + bodyBytes > caps.maxTotalBodyBytes) {
          if (loudFailOnCapExceeded) {
            throw GoalVerificationBoundaryCapExceededError(
              "finding verification boundary body resolution exceeded max_total_body_bytes",
            )
          }
          truncated = true
          unresolved.addAll(requested.subList(index, requested.size))
          break
        }
        totalBytes += bodyBytes
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
