package skillbill.install

import skillbill.install.model.AgentTarget
import java.nio.file.Files
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

  fun linkJunieAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val targetPath = resolvedHome.resolve(".junie/agents")
    val target =
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".junie"))) {
        AgentTarget(JUNIE_AGENTS_KIND, targetPath)
      } else {
        return emptyList()
      }
    val managedRoots = nativeAgentDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms)
    return discoverJunieAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms).mapNotNull { file ->
      installNativeAgentFile(file, target, managedSourceRoots = managedRoots)
    }
  }

  fun unlinkJunieAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> = uninstallJunieAgentMarkdown(platformPacksRoot, home, skillsRoot, selectedPlatforms)
}
