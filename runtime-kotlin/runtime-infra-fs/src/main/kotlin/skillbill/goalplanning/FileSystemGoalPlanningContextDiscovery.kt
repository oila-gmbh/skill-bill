package skillbill.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

@Inject
class FileSystemGoalPlanningContextDiscovery : GoalPlanningContextDiscovery {
  override fun discover(repoRoot: Path): GoalPlanningContext = GoalPlanningContext(
    platformPacks = governedFiles(repoRoot.resolve("platform-packs"), "platform.yaml"),
    boundaryMemory = governedFiles(repoRoot.resolve("platform-packs"), "history.md", "decisions.md"),
    validationGuidance = repoRoot.resolve("AGENTS.md").takeIf(Path::isRegularFile)?.readText().orEmpty(),
  )

  private fun governedFiles(root: Path, vararg names: String): Map<String, String> {
    if (!root.isDirectory()) return emptyMap()
    return Files.walk(root).use { paths ->
      paths.filter { it.isRegularFile() && it.fileName.toString() in names }
        .sorted()
        .iterator()
        .asSequence()
        .associate { path -> root.parent.relativize(path).joinToString("/") to bounded(path.readText()) }
    }
  }

  private fun bounded(value: String): String = value.take(MAX_CONTEXT_FILE_CHARS)

  private companion object {
    const val MAX_CONTEXT_FILE_CHARS = 32_768
  }
}
