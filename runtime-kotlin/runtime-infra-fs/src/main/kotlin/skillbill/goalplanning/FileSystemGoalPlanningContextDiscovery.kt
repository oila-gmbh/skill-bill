package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

@Inject
class FileSystemGoalPlanningContextDiscovery : GoalPlanningContextDiscovery {
  override fun discover(repoRoot: Path): GoalPlanningContext {
    val budget = DiscoveryBudget()
    val canonicalRoot = repoRoot.toAbsolutePath().normalize()
    val packsRoot = canonicalRoot.resolve("platform-packs")
    return GoalPlanningContext(
      platformPacks = discoverPackFiles(canonicalRoot, packsRoot, "platform.yaml", budget),
      boundaryMemory = discoverPackFiles(canonicalRoot, packsRoot, "agent/history.md", budget) +
        discoverPackFiles(canonicalRoot, packsRoot, "agent/decisions.md", budget),
      validationGuidance = readBounded(canonicalRoot.resolve("AGENTS.md"), "AGENTS.md", budget).orEmpty(),
    )
  }

  private fun discoverPackFiles(
    repoRoot: Path,
    packsRoot: Path,
    relativeName: String,
    budget: DiscoveryBudget,
  ): Map<String, String> {
    if (!packsRoot.isDirectory()) return emptyMap()
    val packDirs = Files.list(packsRoot).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }.sorted().toList()
    }
    return packDirs.mapNotNull { packDir ->
      val candidate = packDir.resolve(relativeName)
      val relative = repoRoot.relativize(candidate).joinToString("/")
      readBounded(candidate, relative, budget)?.let { content -> relative to content }
    }.toMap()
  }

  private fun readBounded(path: Path, relative: String, budget: DiscoveryBudget): String? {
    if (!budget.canRead() || !Files.isRegularFile(path)) return null
    val fileBytes = runCatching { Files.size(path) }.getOrNull() ?: return null
    val bytesToRead = minOf(fileBytes, MAX_CONTEXT_EXCERPT_BYTES.toLong(), budget.remainingBytes()).toInt()
    if (bytesToRead <= 0) {
      budget.record(0)
      return null
    }
    val bytes = runCatching {
      Files.newInputStream(path).use { input -> input.readNBytes(bytesToRead) }
    }.getOrNull() ?: return null
    budget.record(bytes.size.toLong())
    val excerpt = bytes.toString(StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .replace('\r', '\n')
    val suffix = if (fileBytes > bytes.size) "\n…[$relative excerpt bounded]" else ""
    return excerpt + suffix
  }

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
