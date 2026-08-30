package skillbill.goalplanning

import java.nio.file.Path

internal data class BoundaryFileRead(val text: String, val cut: Boolean)

internal data class AgentDirectoryWalk(val directories: List<Path>, val incomplete: Boolean)

internal object GoalPlanningRepositoryScope {
  const val AGENT_DIRECTORY = "agent"
  const val MAX_VISITED_DIRECTORIES = 16_384
  val BOUNDARY_MEMORY_FILES = listOf("history.md", "decisions.md")

  fun canonicalRoot(repoRoot: Path): Path = goalPlanningCanonicalRoot(repoRoot)

  fun included(repoRoot: Path, candidate: Path): Pair<Path, String>? =
    goalPlanningIncluded(repoRoot, candidate)

  fun includedRegularFile(repoRoot: Path, relativePath: String): Path? =
    goalPlanningIncludedRegularFile(repoRoot, relativePath)

  fun isBoundaryMemoryPath(relativePath: String): Boolean =
    goalPlanningIsBoundaryMemoryPath(relativePath)

  fun agentDirectories(repoRoot: Path): AgentDirectoryWalk = goalPlanningAgentDirectories(repoRoot)

  fun owningAgentDirectory(repoRoot: Path, findingPath: String): Path? =
    goalPlanningOwningAgentDirectory(repoRoot, findingPath)

  fun owningAgentDirectories(repoRoot: Path, findingPaths: List<String>): List<Path> =
    goalPlanningOwningAgentDirectories(repoRoot, findingPaths)

  fun normalizeFindingPath(findingPath: String): String? = goalPlanningNormalizeFindingPath(findingPath)
}
