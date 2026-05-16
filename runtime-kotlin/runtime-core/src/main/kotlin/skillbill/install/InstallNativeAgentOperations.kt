package skillbill.install

import skillbill.install.model.AgentTarget
import skillbill.nativeagent.NativeAgentInstallRenderOverrides
import skillbill.nativeagent.NativeAgentInstallRenderRequest
import skillbill.nativeagent.NativeAgentOperations
import skillbill.nativeagent.NativeAgentProvider
import skillbill.nativeagent.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.validateNativeAgentArtifactsForInstall
import java.nio.file.Files
import java.nio.file.Path

data class NativeAgentLinkOutcome(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
)

data class NativeAgentSkippedLink(val path: Path, val reason: String)

data class NativeAgentLinkOverrides(
  val installCacheRoot: Path? = null,
  val sourceRoots: List<Path>? = null,
  val legacyManagedRoot: Path? = null,
)

data class NativeAgentLinkRequest(
  val platformPacksRoot: Path,
  val skillsRoot: Path? = null,
  val home: Path? = null,
  val selectedPlatforms: List<String>? = null,
  val overrides: NativeAgentLinkOverrides = NativeAgentLinkOverrides(),
)

object InstallNativeAgentOperations {
  fun linkClaudeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Claude,
    request = request,
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
    request = NativeAgentLinkRequest(platformPacksRoot, skillsRoot, home, selectedPlatforms),
  )

  fun linkCodexAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Codex,
    request = request,
    detectTarget = { detectCodexAgentsTarget(it) },
  )

  fun unlinkCodexAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val request = NativeAgentLinkRequest(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Codex, request = request)
    return uninstallCodexAgentTomls(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) + unlinkedFromCache
  }

  fun linkOpencodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Opencode,
    request = request,
    detectTarget = { detectOpencodeAgentsTarget(it) },
  )

  fun unlinkOpencodeAgents(
    platformPacksRoot: Path,
    skillsRoot: Path? = null,
    home: Path? = null,
    selectedPlatforms: List<String>? = null,
  ): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val request = NativeAgentLinkRequest(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Opencode, request = request)
    return uninstallOpencodeAgentMarkdown(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) +
      unlinkedFromCache
  }

  fun linkJunieAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Junie,
    request = request,
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
    val request = NativeAgentLinkRequest(platformPacksRoot, skillsRoot, resolvedHome, selectedPlatforms)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Junie, request = request)
    return uninstallJunieAgentMarkdown(platformPacksRoot, resolvedHome, skillsRoot, selectedPlatforms) +
      unlinkedFromCache
  }

  private fun linkProviderAgents(
    provider: NativeAgentProvider,
    request: NativeAgentLinkRequest,
    detectTarget: (Path) -> AgentTarget?,
  ): NativeAgentLinkOutcome {
    val validationRoot = nativeAgentCompositionRepoRoot(request.platformPacksRoot, request.skillsRoot)
    request.overrides.sourceRoots
      ?.let { roots -> validateNativeAgentArtifactsForInstall(roots, validationRoot) }
      ?: validateNativeAgentArtifactsForInstall(
        request.platformPacksRoot,
        request.skillsRoot,
        request.selectedPlatforms,
      )
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val target = detectTarget(resolvedHome) ?: return NativeAgentLinkOutcome(emptyList(), emptyList())
    val generated = NativeAgentOperations.renderInstallArtifacts(
      NativeAgentInstallRenderRequest(
        platformPacksRoot = request.platformPacksRoot,
        skillsRoot = request.skillsRoot,
        selectedPlatforms = request.selectedPlatforms,
        provider = provider,
        home = resolvedHome,
        overrides = NativeAgentInstallRenderOverrides(
          cacheRoot = request.overrides.installCacheRoot,
          sourceRoots = request.overrides.sourceRoots,
        ),
      ),
    )
    val managedRoots = listOfNotNull(generated.cacheRoot, request.overrides.legacyManagedRoot)
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

  private fun unlinkProviderAgents(provider: NativeAgentProvider, request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val generated = NativeAgentOperations.renderInstallArtifacts(
      NativeAgentInstallRenderRequest(
        platformPacksRoot = request.platformPacksRoot,
        skillsRoot = request.skillsRoot,
        selectedPlatforms = request.selectedPlatforms,
        provider = provider,
        home = resolvedHome,
      ),
    )
    return uninstallNativeAgentFiles(generated.generatedFiles, provider.homeAgentDirs(resolvedHome))
  }
}
