@file:Suppress("MatchingDeclarationName")

package skillbill.nativeagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

data class NativeAgentValidationReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

fun validateRepoNativeAgents(repoRoot: Path): NativeAgentValidationReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val issues = mutableListOf<String>()
  val sources = NativeAgentOperations.discoverRepoNativeAgentSources(root)
  validateNativeAgentSources(root, sources, issues)
  validateNoCheckedInGeneratedArtifacts(root, issues)
  return NativeAgentValidationReport(issues.sorted())
}

internal fun validateNativeAgentArtifactsForInstall(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
) {
  val sources = discoverNativeAgentSources(platformPacksRoot, skillsRoot, selectedPlatforms)
  if (sources.isEmpty()) {
    return
  }
  val root = commonValidationRoot(platformPacksRoot, skillsRoot)
  val issues = mutableListOf<String>()
  validateNativeAgentSources(root, sources, issues)
  require(issues.isEmpty()) {
    "Native agent sources are invalid:\n${issues.sorted().joinToString("\n")}"
  }
}

private fun validateNativeAgentSources(root: Path, sources: List<Path>, issues: MutableList<String>) {
  val seenNames = mutableMapOf<String, Path>()
  sources.forEach { sourcePath ->
    val source = runCatching { parseNativeAgentSource(sourcePath) }
      .getOrElse { error ->
        issues += "${displayPath(root, sourcePath)}: ${error.message.orEmpty()}"
        return@forEach
      }
    val duplicate = seenNames.putIfAbsent(source.name, sourcePath)
    if (duplicate != null) {
      issues += "${displayPath(root, sourcePath)}: native agent source name '${source.name}' duplicates " +
        displayPath(root, duplicate)
    }
    NativeAgentProvider.entries.forEach { provider ->
      runCatching { renderNativeAgent(source, provider) }.getOrElse { error ->
        issues += "${displayPath(root, sourcePath)}: cannot render ${provider.directoryName}: " +
          error.message.orEmpty()
      }
    }
  }
}

private fun validateNoCheckedInGeneratedArtifacts(root: Path, issues: MutableList<String>) {
  nativeAgentGeneratedArtifacts(root)
    .forEach { artifact ->
      issues += "${displayPath(root, artifact)}: generated native agent artifacts must not be checked in; " +
        "keep the source in $NATIVE_AGENT_SOURCE_DIR/ and let install render provider files"
    }
}

private fun nativeAgentGeneratedArtifacts(root: Path): List<Path> {
  val providerDirs = NativeAgentProvider.entries.map { it.directoryName }.toSet()
  return listOf(root.resolve("skills"), root.resolve("platform-packs"))
    .filter(Files::isDirectory)
    .flatMap { scanRoot ->
      Files.walk(scanRoot).use { stream ->
        stream
          .filter(Files::isRegularFile)
          .filter { file -> file.parent?.fileName?.toString() in providerDirs }
          .toList()
      }
    }.sortedBy { it.toString() }
}

private fun commonValidationRoot(platformPacksRoot: Path, skillsRoot: Path?): Path {
  val platformRoot = platformPacksRoot.toAbsolutePath().normalize()
  val skillRoot = skillsRoot?.toAbsolutePath()?.normalize()
  return if (skillRoot != null && platformRoot.parent == skillRoot.parent) {
    platformRoot.parent
  } else {
    platformRoot
  }
}

private fun displayPath(root: Path, path: Path): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString() }.getOrDefault(resolvedPath.toString())
}
