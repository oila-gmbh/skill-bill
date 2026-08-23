package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.file.Files
import java.nio.file.Path

/**
 * File content, plus whether the read stopped at its cap. A cut file parses into fewer entries than
 * it holds, so a caller that cannot tell the difference publishes a partial catalog as a complete one.
 */
internal data class BoundaryFileRead(val text: String, val cut: Boolean)

/**
 * Directories carrying boundary memory. [incomplete] is true when the walk stopped at its visit cap
 * or could not list a subtree, so the catalog built from it must not claim to be complete.
 */
internal data class AgentDirectoryWalk(val directories: List<Path>, val incomplete: Boolean)

/**
 * The one canonicalize-then-deny gate discovery and body resolution share: a path is in scope only
 * when its real path stays under the repo root and its canonical repo-relative form survives the
 * checked-in exclusion contract, so a symlink cannot re-admit an excluded root.
 */
@Suppress("TooManyFunctions")
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

  /** A repo-relative path is boundary memory only at `<dir>/agent/<governed file>`. */
  fun isBoundaryMemoryPath(relativePath: String): Boolean {
    val segments = relativePath.split("/")
    return segments.size >= 2 &&
      segments[segments.size - 2] == AGENT_DIRECTORY &&
      segments.last() in BOUNDARY_MEMORY_FILES
  }

  /** Bounded deterministic walk: sorted children, excluded roots pruned before descending, cycles cut. */
  fun agentDirectories(repoRoot: Path): AgentDirectoryWalk {
    val found = mutableListOf<Path>()
    val seen = mutableSetOf(repoRoot)
    val pending = ArrayDeque(listOf(repoRoot))
    var visited = 0
    var unlistable = false
    while (pending.isNotEmpty() && visited < MAX_VISITED_DIRECTORIES) {
      visited += 1
      val children = sortedChildDirectories(pending.removeFirst())
      if (children == null) {
        unlistable = true
        continue
      }
      for (canonical in children.mapNotNull { child -> included(repoRoot, child)?.first }) {
        if (!seen.add(canonical)) continue
        if (canonical.fileName.toString() == AGENT_DIRECTORY) found.add(canonical) else pending.add(canonical)
      }
    }
    return AgentDirectoryWalk(
      directories = found.sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") },
      incomplete = pending.isNotEmpty() || unlistable,
    )
  }

  fun owningAgentDirectory(repoRoot: Path, findingPath: String): Path? {
    val normalized = normalizeFindingPath(findingPath) ?: return null
    if (GoalPlanningDiscoveryExclusions.isExcluded(normalized)) return null
    val segments = normalized.split("/")
    for (segmentCount in segments.size downTo 0) {
      val prefix = segments.take(segmentCount).joinToString("/")
      val agentRelative = if (prefix.isEmpty()) AGENT_DIRECTORY else "$prefix/$AGENT_DIRECTORY"
      val agentDir = repoRoot.resolve(agentRelative)
      val includedAgent = included(repoRoot, agentDir) ?: continue
      if (BOUNDARY_MEMORY_FILES.any { fileName -> includedRegularFile(repoRoot, "$agentRelative/$fileName") != null }) {
        return includedAgent.first
      }
    }
    return null
  }

  fun owningAgentDirectories(repoRoot: Path, findingPaths: List<String>): List<Path> =
    findingPaths.mapNotNull { path -> owningAgentDirectory(repoRoot, path) }
      .distinct()
      .sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") }

  fun normalizeFindingPath(findingPath: String): String? {
    val trimmed = findingPath.trim()
    if (trimmed.isBlank()) return null
    return runCatching {
      requireRepositoryRelativePath(trimmed)
      trimmed
    }.getOrNull()
  }

  /** Null means the file exists but could not be read — the caller must treat that as a degradation. */
  fun readFileOrNull(path: Path, maxBytes: Long): BoundaryFileRead? {
    val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val bytes = runCatching {
      Files.newInputStream(path).use { input -> input.readNBytes(cap) }
    }.getOrNull() ?: return null
    return BoundaryFileRead(bytes.decodeToString(), cut = bytes.size >= cap)
  }

  /** Truncates on a code-point boundary so a cap named in bytes is honoured in bytes. */
  fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
    if (text.length <= maxBytes / MAX_UTF8_BYTES_PER_CHAR) return text
    val encoded = text.encodeToByteArray()
    if (encoded.size <= maxBytes) return text
    var end = maxBytes
    while (end > 0 && (encoded[end].toInt() and CONTINUATION_MASK) == CONTINUATION_MARKER) end -= 1
    return encoded.decodeToString(0, end)
  }

  fun utf8Size(text: String): Int = text.encodeToByteArray().size

  /** Null distinguishes an unlistable directory from a genuinely empty one. */
  private fun sortedChildDirectories(directory: Path): List<Path>? = runCatching {
    Files.list(directory).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }
        .sorted()
        .toList()
    }
  }.getOrNull()

  private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()

  private const val MAX_VISITED_DIRECTORIES = 16_384
  private const val MAX_UTF8_BYTES_PER_CHAR = 3
  private const val CONTINUATION_MASK = 0xC0
  private const val CONTINUATION_MARKER = 0x80
}
