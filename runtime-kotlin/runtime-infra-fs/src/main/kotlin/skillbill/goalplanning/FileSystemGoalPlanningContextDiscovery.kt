package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Path

@Inject
class FileSystemGoalPlanningContextDiscovery : GoalPlanningContextDiscovery {
  override fun discover(repoRoot: Path): GoalPlanningContext {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    // Load-bearing discovery order — not incidental argument order: agent/history.md then
    // agent/decisions.md per agent directory, over agent directories sorted lexicographically by
    // repo-relative path. Catalog caps truncate at that deterministic boundary, so the same tree
    // yields the same catalog and the same truncation marker on every run. Every candidate is
    // canonicalized and denied by repo-relative path against the checked-in exclusion contract, so
    // platform-packs/ never contributes planning memory. The catalog carries headings only; bodies
    // are resolved later for selected headings by FileSystemGoalPlanningBoundaryBodyResolver.
    val catalog = discoverCatalog(canonicalRoot)
    return GoalPlanningContext(
      boundaryCatalog = catalog.headings,
      boundaryCatalogTruncated = catalog.truncated,
      validationGuidance = readValidationGuidance(canonicalRoot),
    )
  }

  private fun discoverCatalog(repoRoot: Path): Catalog {
    val headings = mutableListOf<GoalPlanningBoundaryHeading>()
    var truncated = false
    var files = 0
    var bytes = 0L
    for (agentDir in GoalPlanningRepositoryScope.agentDirectories(repoRoot)) {
      for (fileName in GoalPlanningRepositoryScope.BOUNDARY_MEMORY_FILES) {
        val (canonical, relative) = GoalPlanningRepositoryScope.included(repoRoot, agentDir.resolve(fileName))
          ?: continue
        if (files >= GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT ||
          bytes >= GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES
        ) {
          return Catalog(headings, truncated = true)
        }
        val content = GoalPlanningRepositoryScope.readTextOrNull(
          canonical,
          GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES - bytes,
        ) ?: continue
        files += 1
        bytes += content.length.toLong()
        val entries = BoundaryMemoryHeadingParser.parse(relative, content)
        if (entries.size > GoalPlanningContext.MAX_HEADINGS_PER_FILE) truncated = true
        for (entry in entries.take(GoalPlanningContext.MAX_HEADINGS_PER_FILE)) {
          if (headings.size >= GoalPlanningContext.MAX_CATALOG_HEADINGS) return Catalog(headings, truncated = true)
          headings.add(
            GoalPlanningBoundaryHeading(
              headingId = entry.headingId,
              sourcePath = relative,
              kind = kindOf(fileName),
              heading = entry.heading.take(GoalPlanningContext.MAX_HEADING_TEXT_CHARS),
            ),
          )
        }
      }
    }
    return Catalog(headings, truncated)
  }

  private fun readValidationGuidance(repoRoot: Path): String {
    val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, "AGENTS.md") ?: return ""
    val content = GoalPlanningRepositoryScope.readTextOrNull(
      canonical,
      GoalPlanningContext.MAX_VALIDATION_GUIDANCE_BYTES.toLong(),
    ) ?: return ""
    return content.replace("\r\n", "\n").replace('\r', '\n')
  }

  private fun kindOf(fileName: String): String =
    if (fileName == "history.md") GoalPlanningContext.KIND_HISTORY else GoalPlanningContext.KIND_DECISIONS

  private data class Catalog(val headings: List<GoalPlanningBoundaryHeading>, val truncated: Boolean)
}
