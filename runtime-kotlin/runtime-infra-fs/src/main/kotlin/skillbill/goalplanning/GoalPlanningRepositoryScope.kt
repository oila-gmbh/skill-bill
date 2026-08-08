package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one canonicalize-then-deny gate discovery and body resolution share: a path is in scope only
 * when its real path stays under the repo root and its canonical repo-relative form survives the
 * checked-in exclusion contract, so a symlink cannot re-admit an excluded root.
 */
internal object GoalPlanningRepositoryScope {
  const val AGENT_DIRECTORY = "agent"
  val BOUNDARY_MEMORY_FILES = listOf("history.md", "decisions.md")

  fun canonicalRoot(repoRoot: Path): Path = repoRoot.toRealPathOrNull() ?: repoRoot.toAbsolutePath().normalize()

  /** Canonical path plus repo-relative path, or null when it escapes the repo or the contract denies it. */
  fun included(repoRoot: Path, candidate: Path): Pair<Path, String>? {
    val canonical = candidate.toRealPathOrNull()?.takeIf { path -> path.startsWith(repoRoot) } ?: return null
    val relative = repoRoot.relativize(canonical).joinToString("/")
    if (relative.isEmpty() || GoalPlanningDiscoveryExclusions.isExcluded(relative)) return null
    return canonical to relative
  }

  fun includedRegularFile(repoRoot: Path, relativePath: String): Path? {
    if (relativePath.isBlank() || GoalPlanningDiscoveryExclusions.isExcluded(relativePath)) return null
    val (canonical, canonicalRelative) = included(repoRoot, repoRoot.resolve(relativePath)) ?: return null
    if (canonicalRelative != relativePath) return null
    return canonical.takeIf { path -> Files.isRegularFile(path) }
  }

  /** Bounded deterministic walk: sorted children, excluded roots pruned before descending, cycles cut. */
  fun agentDirectories(repoRoot: Path): List<Path> {
    val found = mutableListOf<Path>()
    val seen = mutableSetOf(repoRoot)
    val pending = ArrayDeque(listOf(repoRoot))
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_VISITED_DIRECTORIES) {
      visited += 1
      val included = sortedChildDirectories(pending.removeFirst())
        .mapNotNull { child -> included(repoRoot, child)?.first }
      for (canonical in included) {
        if (!seen.add(canonical)) continue
        if (canonical.fileName.toString() == AGENT_DIRECTORY) found.add(canonical) else pending.add(canonical)
      }
    }
    return found.sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") }
  }

  fun readTextOrNull(path: Path, maxBytes: Long): String? = runCatching {
    Files.newInputStream(path).use { input -> input.readNBytes(maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
  }.getOrNull()?.decodeToString()

  private fun sortedChildDirectories(directory: Path): List<Path> = runCatching {
    Files.list(directory).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }
        .sorted()
        .toList()
    }
  }.getOrDefault(emptyList())

  private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()

  // shortcut: flat directory-visit cap, revisit if a repo legitimately nests deeper than this
  private const val MAX_VISITED_DIRECTORIES = 4_096
}
