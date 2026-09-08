package skillbill.nativeagent.discovery

import skillbill.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
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

internal fun discoverNativeAgentSourceEntriesInRoots(roots: List<Path>): List<NativeAgentSource> =
  discoverNativeAgentSourceFilesInRoots(roots).flatMap(::parseNativeAgentSourceFile)

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
  return discoverNativeAgentFilesByDirInRoots(
    roots = nativeAgentSourceDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms),
    directoryName = directoryName,
    extension = extension,
  )
}

internal fun discoverNativeAgentSourceFilesInRoots(roots: List<Path>): List<Path> =
  discoverNativeAgentFilesByDirInRoots(
    roots = roots,
    directoryName = NATIVE_AGENT_SOURCE_DIR,
    recursive = false,
  )

private fun discoverNativeAgentFilesByDirInRoots(
  roots: List<Path>,
  directoryName: String,
  extension: String? = null,
  recursive: Boolean = true,
): List<Path> {
  val discovered = linkedSetOf<Path>()
  roots.map { root -> root.toAbsolutePath().normalize() }.forEach { root ->
    if (recursive) {
      if (Files.isDirectory(root)) {
        val allowedRoot = root.toRealPath()
        Files.walk(root).use { stream ->
          stream
            .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
            .filter { file -> file.parent?.name == directoryName }
            .filter { file -> isNativeAgentSourceFile(file, extension) }
            .map { file -> file.toRealPath() }
            .filter { file -> file.startsWith(allowedRoot) }
            .forEach(discovered::add)
        }
      }
    } else {
      val sourceDir = root.resolve(directoryName)
      if (Files.isDirectory(sourceDir)) {
        val allowedRoot = sourceDir.toRealPath()
        Files.list(sourceDir).use { stream ->
          stream
            .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
            .filter { file -> isNativeAgentSourceFile(file, extension) }
            .map { file -> file.toRealPath() }
            .filter { file -> file.startsWith(allowedRoot) }
            .forEach(discovered::add)
        }
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
