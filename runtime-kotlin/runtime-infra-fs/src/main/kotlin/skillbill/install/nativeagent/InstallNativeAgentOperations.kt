package skillbill.install.nativeagent

import skillbill.install.model.AgentTarget
import skillbill.install.plan.CLAUDE_AGENTS_KIND
import skillbill.install.plan.CURSOR_AGENTS_KIND
import skillbill.install.plan.JUNIE_AGENTS_KIND
import skillbill.nativeagent.rendering.NativeAgentProvider
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
  fun linkClaudeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    return linkProviderAgents(
      provider = NativeAgentProvider.Claude,
      request = request,
      detectTargets = {
        NativeAgentProvider.Claude.homeAgentDirs(resolvedHome).map { AgentTarget(CLAUDE_AGENTS_KIND, it) }
      },
    )
  }

  fun unlinkClaudeAgents(request: NativeAgentLinkRequest): List<Path> = unlinkProviderAgents(
    provider = NativeAgentProvider.Claude,
    request = request,
  )

  fun linkCodexAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Codex,
    request = request,
    detectTargets = { home ->
      NativeAgentProvider.Codex.activeHomeAgentDirs(home).map { AgentTarget("codex-agents", it) }
    },
  )

  fun unlinkCodexAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Codex, request = request)
    return uninstallCodexAgentTomls(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) + unlinkedFromCache
  }

  fun linkJunieAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Junie,
    request = request,
    detectTargets = { resolvedHome ->
      val targetPath = NativeAgentProvider.Junie.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".junie"))) {
        listOf(AgentTarget(JUNIE_AGENTS_KIND, targetPath))
      } else {
        emptyList()
      }
    },
  )

  fun unlinkJunieAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Junie, request = request)
    return uninstallJunieAgentMarkdown(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) +
      unlinkedFromCache
  }

  fun linkCursorAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Cursor,
    request = request,
    detectTargets = { resolvedHome ->
      val targetPath = NativeAgentProvider.Cursor.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".cursor"))) {
        listOf(AgentTarget(CURSOR_AGENTS_KIND, targetPath))
      } else {
        emptyList()
      }
    },
  )

  fun unlinkCursorAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Cursor, request = request)
    return uninstallCursorAgentMarkdown(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) +
      unlinkedFromCache
  }
}
