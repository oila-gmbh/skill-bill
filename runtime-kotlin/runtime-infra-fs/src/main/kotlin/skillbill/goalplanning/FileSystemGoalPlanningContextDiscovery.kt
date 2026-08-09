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
    // repo-relative path. Every candidate is canonicalized and denied by repo-relative path against
    // the checked-in exclusion contract, so platform-packs/ never contributes planning memory. The
    // catalog carries headings only; bodies are resolved later for selected headings by
    // FileSystemGoalPlanningBoundaryBodyResolver, which reads under the same per-file cap so both
    // passes parse identical text and produce identical heading ids.
    val catalog = discoverCatalog(canonicalRoot)
    return GoalPlanningContext(
      boundaryCatalog = catalog.headings,
      boundaryCatalogTruncated = catalog.truncated,
      validationGuidance = readValidationGuidance(canonicalRoot),
    )
  }

  private fun discoverCatalog(repoRoot: Path): Catalog {
    val walk = GoalPlanningRepositoryScope.agentDirectories(repoRoot)
    val candidates = candidateFiles(repoRoot, walk.directories)
    val eligible = candidates.take(GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT)
    var truncated = walk.incomplete || eligible.size < candidates.size
    val perFile = mutableListOf<List<GoalPlanningBoundaryHeading>>()
    for (candidate in eligible) {
      val read = GoalPlanningRepositoryScope.readFileOrNull(
        candidate.canonical,
        GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES,
      )
      if (read == null) {
        // The file is present and in scope but unreadable. Skipping it silently would publish a
        // catalog that claims completeness while a whole module's memory is missing.
        truncated = true
      } else {
        // A file cut at the per-file cap parses into fewer entries than it holds. Reporting it keeps
        // the catalog from claiming completeness it does not have.
        if (read.cut) truncated = true
        val entries = BoundaryMemoryHeadingParser.parse(candidate.relative, read.text)
        if (entries.size > GoalPlanningContext.MAX_HEADINGS_PER_FILE) truncated = true
        perFile.add(
          entries.take(GoalPlanningContext.MAX_HEADINGS_PER_FILE).map { entry ->
            GoalPlanningBoundaryHeading(
              headingId = entry.headingId,
              sourcePath = candidate.relative,
              kind = candidate.kind,
              heading = entry.heading.take(GoalPlanningContext.MAX_HEADING_TEXT_CHARS),
            )
          },
        )
      }
    }
    val quotas = fairQuotas(perFile.map(List<GoalPlanningBoundaryHeading>::size))
    return Catalog(
      headings = perFile.flatMapIndexed { index, headings -> headings.take(quotas[index]) },
      truncated = truncated || perFile.indices.any { index -> quotas[index] < perFile[index].size },
    )
  }

  private fun candidateFiles(repoRoot: Path, agentDirectories: List<Path>): List<Candidate> =
    agentDirectories.flatMap { agentDir ->
      GoalPlanningRepositoryScope.BOUNDARY_MEMORY_FILES.mapNotNull { fileName ->
        GoalPlanningRepositoryScope.included(repoRoot, agentDir.resolve(fileName))
          ?.let { (canonical, relative) -> Candidate(canonical, relative, kindOf(fileName)) }
      }
    }

  /**
   * Spends the catalog cap round-robin across files. A straight sequential take would give the whole
   * cap to whichever module sorts first, so one large early history file would hide every later
   * module's boundary memory outright — the bigger it grew, the more it hid.
   */
  private fun fairQuotas(sizes: List<Int>): List<Int> {
    val quotas = MutableList(sizes.size) { 0 }
    var remaining = GoalPlanningContext.MAX_CATALOG_HEADINGS
    var progressed = true
    while (remaining > 0 && progressed) {
      progressed = false
      for (index in sizes.indices) {
        if (remaining > 0 && quotas[index] < sizes[index]) {
          quotas[index] += 1
          remaining -= 1
          progressed = true
        }
      }
    }
    return quotas
  }

  private fun readValidationGuidance(repoRoot: Path): String {
    val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, "AGENTS.md") ?: return ""
    val read = GoalPlanningRepositoryScope.readFileOrNull(
      canonical,
      GoalPlanningContext.MAX_VALIDATION_GUIDANCE_BYTES.toLong(),
    ) ?: return ""
    return read.text.replace("\r\n", "\n").replace('\r', '\n')
  }

  private fun kindOf(fileName: String): String =
    if (fileName == "history.md") GoalPlanningContext.KIND_HISTORY else GoalPlanningContext.KIND_DECISIONS

  private data class Candidate(val canonical: Path, val relative: String, val kind: String)

  private data class Catalog(val headings: List<GoalPlanningBoundaryHeading>, val truncated: Boolean)
}
