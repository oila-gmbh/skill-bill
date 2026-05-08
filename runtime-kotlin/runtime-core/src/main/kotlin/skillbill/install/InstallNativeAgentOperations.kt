package skillbill.install

import skillbill.install.model.AgentTarget
import skillbill.nativeagent.NativeAgentOperations
import skillbill.nativeagent.NativeAgentProvider
import skillbill.nativeagent.validateNativeAgentArtifactsForInstall
import java.nio.file.Files
import java.nio.file.Path

data class NativeAgentLinkOutcome(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
)

data class NativeAgentSkippedLink(val path: Path, val reason: String)

private data class NativeAgentInstallScope(
  val platformPacksRoot: Path,
  val skillsRoot: Path?,
  val home: Path?,
  val selectedPlatforms: List<String>?,
)

object InstallNativeAgentOperations {
  fun linkClaudeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Claude,
    scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, home, selectedPlatforms),
    detectTarget = { resolvedHome ->
      val targetPath = NativeAgentProvider.Claude.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".claude"))) {
        AgentTarget(CLAUDE_AGENTS_KIND, targetPath)
      } else {
        null
      }
    },
  )

  fun unlinkClaudeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> = unlinkProviderAgents(
    provider = NativeAgentProvider.Claude,
    scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, home, selectedPlatforms),
  )

  fun linkCodexAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Codex,
    scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, home, selectedPlatforms),
    detectTarget = { detectCodexAgentsTarget(it) },
  )

  fun unlinkCodexAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Codex, scope = scope)
    return uninstallCodexAgentTomls(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) + unlinkedFromCache
  }

  fun linkOpencodeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Opencode,
    scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, home, selectedPlatforms),
    detectTarget = { detectOpencodeAgentsTarget(it) },
  )

  fun unlinkOpencodeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Opencode, scope = scope)
    return uninstallOpencodeAgentMarkdown(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) +
      unlinkedFromCache
  }

  fun linkJunieAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Junie,
    scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, home, selectedPlatforms),
    detectTarget = { resolvedHome ->
      val targetPath = NativeAgentProvider.Junie.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".junie"))) {
        AgentTarget(JUNIE_AGENTS_KIND, targetPath)
      } else {
        null
      }
    },
  )

  fun unlinkJunieAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val scope = NativeAgentInstallScope(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Junie, scope = scope)
    return uninstallJunieAgentMarkdown(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) +
      unlinkedFromCache
  }

  private fun linkProviderAgents(
    provider: NativeAgentProvider,
    scope: NativeAgentInstallScope,
    detectTarget: (Path) -> AgentTarget?,
  ): NativeAgentLinkOutcome {
    validateNativeAgentArtifactsForInstall(scope.platformPacksRoot, scope.skillsRoot, scope.selectedPlatforms)
    val resolvedHome = scope.home ?: Path.of(System.getProperty("user.home"))
    val target = detectTarget(resolvedHome) ?: return NativeAgentLinkOutcome(emptyList(), emptyList())
    val generated = NativeAgentOperations.renderInstallArtifacts(
      platformPacksRoot = scope.platformPacksRoot,
      skillsRoot = scope.skillsRoot,
      selectedPlatforms = scope.selectedPlatforms,
      provider = provider,
      home = resolvedHome,
    )
    val managedRoots = nativeAgentDiscoveryRoots(
      scope.platformPacksRoot,
      scope.skillsRoot,
      scope.selectedPlatforms,
    ) + generated.cacheRoot
    val linked = mutableListOf<Path>()
    val skipped = mutableListOf<NativeAgentSkippedLink>()
    generated.generatedFiles.forEach { file ->
      when (val result = installNativeAgentFile(file, target, managedSourceRoots = managedRoots)) {
        is InstallNativeAgentResult.Linked -> linked.add(result.link)
        is InstallNativeAgentResult.Skipped -> skipped.add(NativeAgentSkippedLink(result.link, result.reason))
      }
    }
    return NativeAgentLinkOutcome(linked, skipped)
  }

  private fun unlinkProviderAgents(provider: NativeAgentProvider, scope: NativeAgentInstallScope): List<Path> {
    val resolvedHome = scope.home ?: Path.of(System.getProperty("user.home"))
    val generated = NativeAgentOperations.renderInstallArtifacts(
      platformPacksRoot = scope.platformPacksRoot,
      skillsRoot = scope.skillsRoot,
      selectedPlatforms = scope.selectedPlatforms,
      provider = provider,
      home = resolvedHome,
    )
    return uninstallNativeAgentFiles(generated.generatedFiles, provider.homeAgentDirs(resolvedHome))
  }
}
