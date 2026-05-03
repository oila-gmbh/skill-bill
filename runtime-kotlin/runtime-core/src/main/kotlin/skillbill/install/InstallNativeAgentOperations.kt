package skillbill.install

import java.nio.file.Path

object InstallNativeAgentOperations {
  fun linkCodexAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val target = detectCodexAgentsTarget(home) ?: return emptyList()
    val managedRoots = nativeAgentDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms)
    return discoverCodexAgentTomls(platformPacksRoot, skillsRoot, selectedPlatforms).mapNotNull { file ->
      installNativeAgentFile(file, target, managedSourceRoots = managedRoots)
    }
  }

  fun unlinkCodexAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> = uninstallCodexAgentTomls(platformPacksRoot, home, skillsRoot, selectedPlatforms)

  fun linkOpencodeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val target = detectOpencodeAgentsTarget(home) ?: return emptyList()
    val managedRoots = nativeAgentDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms)
    return discoverOpencodeAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms).mapNotNull { file ->
      installNativeAgentFile(file, target, managedSourceRoots = managedRoots)
    }
  }

  fun unlinkOpencodeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> = uninstallOpencodeAgentMarkdown(platformPacksRoot, home, skillsRoot, selectedPlatforms)
}
