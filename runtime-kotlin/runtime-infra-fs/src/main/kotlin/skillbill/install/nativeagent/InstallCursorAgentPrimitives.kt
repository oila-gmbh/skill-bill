package skillbill.install.nativeagent

import skillbill.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

internal fun discoverCursorAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NativeAgentProvider.Cursor.directoryName,
  extension = NativeAgentProvider.Cursor.extension,
)

internal fun uninstallCursorAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverCursorAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Cursor.homeAgentDirs(resolvedHome),
  )
}
