package skillbill.nativeagent

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

fun discoverNativeAgentSources(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentSourceFiles(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
)

fun discoverNativeAgentSourceEntries(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<NativeAgentSource> = discoverNativeAgentSourceFiles(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
).flatMap(::parseNativeAgentSourceFile)

fun discoverNativeAgentSourceFiles(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NATIVE_AGENT_SOURCE_DIR,
)

fun discoverNativeAgentFilesByDir(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
  directoryName: String,
  extension: String? = null,
): List<Path> {
  val discovered = linkedSetOf<Path>()
  nativeAgentSourceDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms).forEach { root ->
    if (Files.isDirectory(root)) {
      val allowedRoot = root.toRealPath()
      Files.walk(root).use { stream ->
        stream
          .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
          .filter { file ->
            file.parent?.name == directoryName && isNativeAgentSourceFile(file, extension)
          }
          .map { file -> file.toRealPath() }
          .filter { file -> file.startsWith(allowedRoot) }
          .forEach(discovered::add)
      }
    }
  }
  return discovered.sortedBy { it.toString() }
}

fun nativeAgentSourceDiscoveryRoots(
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

private fun isNativeAgentSourceFile(file: Path, extension: String?): Boolean {
  val fileName = file.fileName.toString()
  return if (extension == null) {
    fileName.endsWith(".md") || fileName == NATIVE_AGENT_BUNDLE_FILE
  } else {
    fileName.endsWith(".$extension")
  }
}
