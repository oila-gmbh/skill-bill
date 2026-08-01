package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
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
    val packsRoot = canonicalRoot.resolve("platform-packs")
    return GoalPlanningContext(
      platformPacks = discoverPackFiles(canonicalRoot, packsRoot, "platform.yaml", budget),
      boundaryMemory = discoverPackFiles(canonicalRoot, packsRoot, "agent/history.md", budget) +
        discoverPackFiles(canonicalRoot, packsRoot, "agent/decisions.md", budget),
      validationGuidance = readBounded(
        canonicalRoot,
        canonicalRoot.resolve("AGENTS.md"),
        "AGENTS.md",
        budget,
      ).orEmpty(),
    )
  }

  private fun discoverPackFiles(
    repoRoot: Path,
    packsRoot: Path,
    relativeName: String,
    budget: DiscoveryBudget,
  ): Map<String, String> {
    val canonicalPacksRoot = packsRoot.toRealPathOrNull()?.takeIf { it.startsWith(repoRoot) } ?: return emptyMap()
    if (!Files.isDirectory(canonicalPacksRoot)) return emptyMap()
    val packDirs = Files.list(canonicalPacksRoot).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }
        .sorted()
        .toList()
    }.mapNotNull { path -> path.toRealPathOrNull()?.takeIf { it.startsWith(repoRoot) } }
    return packDirs.mapNotNull { packDir ->
      val candidate = packDir.resolve(relativeName)
      val relative = repoRoot.relativize(candidate).joinToString("/")
      readBounded(repoRoot, candidate, relative, budget)?.let { content -> relative to content }
    }.toMap()
  }

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
    const val MAX_CONTEXT_FILE_COUNT = GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT
    const val MAX_CONTEXT_EXCERPT_BYTES = GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES
    const val MAX_CONTEXT_TOTAL_BYTES = GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES
  }
}
