package skillbill.install.nativeagent

import skillbill.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.nativeagent.discovery.nativeAgentSourceDiscoveryRoots
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

internal fun discoverOpencodeAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NativeAgentProvider.Opencode.directoryName,
  extension = NativeAgentProvider.Opencode.extension,
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

internal fun uninstallOpencodeAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverOpencodeAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Opencode.homeAgentDirs(resolvedHome),
  )
}

internal fun nativeAgentDiscoveryRoots(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
): List<Path> = nativeAgentSourceDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms)
