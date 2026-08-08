package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemGoalPlanningContextDiscovery : GoalPlanningContextDiscovery {
  override fun discover(repoRoot: Path): GoalPlanningContext {
    val budget = DiscoveryBudget()
    val canonicalRoot = repoRoot.toRealPathOrNull() ?: repoRoot.toAbsolutePath().normalize()
    // Load-bearing discovery priority under the shared DiscoveryBudget — not incidental argument order:
    // (1) boundary_memory — agent/history.md then agent/decisions.md per agent directory, over agent
    //     directories sorted lexicographically by repo-relative path; (2) validation_guidance — root
    //     AGENTS.md last. When file-count or total-byte budget exhausts, later categories are omitted
    //     entirely; within a category, sorted repo-relative order decides which files receive excerpts.
    // Every candidate directory and file is denied by canonical repo-relative path against the
    // checked-in exclusion contract, so platform-packs/ never contributes planning memory.
    return GoalPlanningContext(
      boundaryMemory = discoverBoundaryMemory(canonicalRoot, budget),
      validationGuidance = readBounded(
        canonicalRoot,
        canonicalRoot.resolve("AGENTS.md"),
        "AGENTS.md",
        budget,
      ).orEmpty(),
    )
  }

  private fun discoverBoundaryMemory(repoRoot: Path, budget: DiscoveryBudget): Map<String, String> {
    val memory = linkedMapOf<String, String>()
    for (agentDir in agentDirectories(repoRoot)) {
      for (fileName in BOUNDARY_MEMORY_FILES) {
        val candidate = agentDir.resolve(fileName)
        val canonical = candidate.toRealPathOrNull()?.takeIf { it.startsWith(repoRoot) } ?: continue
        val relative = repoRoot.relativize(canonical).joinToString("/")
        if (GoalPlanningDiscoveryExclusions.isExcluded(relative)) continue
        readBounded(repoRoot, canonical, relative, budget)?.let { content -> memory[relative] = content }
      }
    }
    return memory
  }

  /** Bounded deterministic walk: sorted children, excluded roots pruned before descending, cycles cut. */
  private fun agentDirectories(repoRoot: Path): List<Path> {
    val found = mutableListOf<Path>()
    val seen = mutableSetOf(repoRoot)
    val pending = ArrayDeque(listOf(repoRoot))
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_VISITED_DIRECTORIES) {
      visited += 1
      for (child in sortedChildDirectories(pending.removeFirst())) {
        val canonical = child.toRealPathOrNull()?.takeIf { it.startsWith(repoRoot) } ?: continue
        val relative = repoRoot.relativize(canonical).joinToString("/")
        if (relative.isEmpty() || GoalPlanningDiscoveryExclusions.isExcluded(relative)) continue
        if (!seen.add(canonical)) continue
        if (canonical.fileName.toString() == AGENT_DIRECTORY) found.add(canonical) else pending.add(canonical)
      }
    }
    return found.sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") }
  }

  private fun sortedChildDirectories(directory: Path): List<Path> = runCatching {
    Files.list(directory).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }
        .sorted()
        .toList()
    }
  }.getOrDefault(emptyList())

  private fun readBounded(repoRoot: Path, path: Path, relative: String, budget: DiscoveryBudget): String? {
    val readable = boundedReadableFile(repoRoot, path, budget) ?: return null
    if (readable.bytesToRead <= 0) {
      budget.record(0)
      return null
    }
    val bytes = runCatching {
      Files.newInputStream(readable.path).use { input -> input.readNBytes(readable.bytesToRead) }
    }.getOrNull() ?: return null
    budget.record(bytes.size.toLong())
    val excerpt = bytes.toString(StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .replace('\r', '\n')
    val suffix = if (readable.fileBytes > bytes.size) "\n…[$relative excerpt bounded]" else ""
    return excerpt + suffix
  }

  private fun boundedReadableFile(repoRoot: Path, path: Path, budget: DiscoveryBudget): BoundedReadableFile? =
    if (!budget.canRead()) {
      null
    } else {
      path.toRealPathOrNull()
        ?.takeIf { canonical -> canonical.startsWith(repoRoot) && Files.isRegularFile(canonical) }
        ?.let { canonical ->
          runCatching { Files.size(canonical) }.getOrNull()?.let { fileBytes ->
            BoundedReadableFile(
              path = canonical,
              fileBytes = fileBytes,
              bytesToRead = minOf(
                fileBytes,
                MAX_CONTEXT_EXCERPT_BYTES.toLong(),
                budget.remainingBytes(),
              ).toInt(),
            )
          }
        }
    }

  private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()

  private data class BoundedReadableFile(
    val path: Path,
    val fileBytes: Long,
    val bytesToRead: Int,
  )

  private class DiscoveryBudget {
    private var fileCount = 0
    private var consumedBytes = 0L

    fun canRead(): Boolean = fileCount < MAX_CONTEXT_FILE_COUNT && consumedBytes < MAX_CONTEXT_TOTAL_BYTES

    fun remainingBytes(): Long = MAX_CONTEXT_TOTAL_BYTES - consumedBytes

    fun record(bytes: Long) {
      fileCount += 1
      consumedBytes += bytes
    }
  }

  private companion object {
    const val AGENT_DIRECTORY = "agent"
    val BOUNDARY_MEMORY_FILES = listOf("history.md", "decisions.md")

    // shortcut: flat directory-visit cap, revisit if a repo legitimately nests deeper than this
    const val MAX_VISITED_DIRECTORIES = 4_096
    const val MAX_CONTEXT_FILE_COUNT = GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT
    const val MAX_CONTEXT_EXCERPT_BYTES = GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES
    const val MAX_CONTEXT_TOTAL_BYTES = GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES
  }
}
