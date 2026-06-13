package skillbill.install.nativeagent

import skillbill.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

internal fun discoverJunieAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NativeAgentProvider.Junie.directoryName,
  extension = NativeAgentProvider.Junie.extension,
)

internal fun uninstallJunieAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverJunieAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Junie.homeAgentDirs(resolvedHome),
  )
}
