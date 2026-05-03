package skillbill.install

import skillbill.install.model.AgentTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

internal fun discoverCodexAgentTomls(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFiles(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = "codex-agents",
  extension = "toml",
)

internal fun discoverOpencodeAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFiles(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = "opencode-agents",
  extension = "md",
)

internal fun uninstallCodexAgentTomls(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  val candidateDirs = listOf(
    resolvedHome.resolve(".codex/agents"),
    resolvedHome.resolve(".agents/agents"),
  )
  return uninstallNativeAgentFiles(
    discoverCodexAgentTomls(platformPacksRoot, skillsRoot, selectedPlatforms),
    candidateDirs,
  )
}

internal fun uninstallOpencodeAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverOpencodeAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    listOf(opencodeAgentsPath(resolvedHome)),
  )
}

private fun discoverNativeAgentFiles(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
  directoryName: String,
  extension: String,
): List<Path> {
  val discovered = linkedSetOf<Path>()
  nativeAgentDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms).forEach { root ->
    if (Files.isDirectory(root)) {
      val allowedRoot = root.toRealPath()
      Files.walk(root).use { stream ->
        stream
          .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
          .filter { file ->
            file.parent?.name == directoryName &&
              file.fileName.toString().endsWith(".$extension")
          }
          .map { file -> file.toRealPath() }
          .filter { file -> file.startsWith(allowedRoot) }
          .forEach(discovered::add)
      }
    }
  }
  return discovered.sortedBy { it.toString() }
}

internal fun nativeAgentDiscoveryRoots(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
): List<Path> = if (selectedPlatforms == null) {
  listOfNotNull(platformPacksRoot, skillsRoot)
} else {
  listOfNotNull(skillsRoot) +
    selectedPlatforms.flatMap { platform ->
      listOfNotNull(platformPacksRoot.resolve(platform), skillsRoot?.resolve(platform))
    }
}.map { root -> root.toAbsolutePath().normalize() }

internal fun installNativeAgentFile(source: Path, agentTarget: AgentTarget, managedSourceRoots: List<Path>): Path? {
  val resolvedSource = source.toAbsolutePath().normalize()
  if (!Files.isRegularFile(resolvedSource)) {
    throw java.io.FileNotFoundException("Native agent file '$resolvedSource' does not exist.")
  }
  Files.createDirectories(agentTarget.path)
  val linkPath = agentTarget.path.resolve(resolvedSource.fileName)
  val shouldCreate = when {
    Files.isSymbolicLink(linkPath) -> {
      val existingTarget = resolveSymlinkTarget(linkPath)
      when {
        existingTarget == resolvedSource -> false
        existingTarget != null && managedSourceRoots.any { root -> existingTarget.startsWith(root) } -> {
          Files.deleteIfExists(linkPath)
          true
        }
        else -> false
      }
    }
    Files.exists(linkPath) -> false
    else -> true
  }
  if (shouldCreate) {
    Files.createSymbolicLink(linkPath, resolvedSource)
  }
  return linkPath.takeIf { shouldCreate }
}

private fun uninstallNativeAgentFiles(sources: List<Path>, candidateDirs: List<Path>): List<Path> {
  val removed = mutableListOf<Path>()
  sources.forEach { source ->
    val resolvedSource = source.toAbsolutePath().normalize()
    candidateDirs.forEach { targetDir ->
      val linkPath = targetDir.resolve(resolvedSource.fileName)
      val existingTarget = if (Files.isSymbolicLink(linkPath)) resolveSymlinkTarget(linkPath) else null
      if (existingTarget == resolvedSource) {
        Files.deleteIfExists(linkPath)
        removed.add(linkPath)
      }
    }
  }
  return removed
}

private fun resolveSymlinkTarget(linkPath: Path): Path? = runCatching {
  val rawTarget = Files.readSymbolicLink(linkPath)
  val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
  resolvedTarget.toAbsolutePath().normalize()
}.getOrNull()
