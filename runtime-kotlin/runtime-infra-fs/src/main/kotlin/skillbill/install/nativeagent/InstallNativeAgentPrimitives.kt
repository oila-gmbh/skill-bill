package skillbill.install.nativeagent

import skillbill.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

internal fun discoverCodexAgentTomls(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NativeAgentProvider.Codex.directoryName,
  extension = NativeAgentProvider.Codex.extension,
)

internal fun uninstallCodexAgentTomls(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverCodexAgentTomls(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Codex.homeAgentDirs(resolvedHome),
  )
}
