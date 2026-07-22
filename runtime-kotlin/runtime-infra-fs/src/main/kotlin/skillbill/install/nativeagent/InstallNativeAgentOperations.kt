package skillbill.install.nativeagent

import skillbill.error.MissingInstalledNativeAgentError
import skillbill.install.model.AgentTarget
import skillbill.install.plan.CLAUDE_AGENTS_KIND
import skillbill.install.plan.JUNIE_AGENTS_KIND
import skillbill.install.plan.ZCODE_AGENTS_KIND
import skillbill.install.plan.detectCodexAgentsTarget
import skillbill.install.plan.detectOpencodeAgentsTarget
import skillbill.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.validation.validateNativeAgentArtifactsForInstall
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.security.MessageDigest

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

@Suppress("TooManyFunctions", "LongMethod", "TooGenericExceptionCaught")
object InstallNativeAgentOperations {
  fun linkClaudeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val agentDirs = NativeAgentProvider.Claude.homeAgentDirs(resolvedHome)
    val linked = mutableListOf<Path>()
    val skipped = mutableListOf<NativeAgentSkippedLink>()
    agentDirs.forEach { agentDir ->
      val outcome = linkProviderAgents(
        provider = NativeAgentProvider.Claude,
        request = request,
        detectTarget = { AgentTarget(CLAUDE_AGENTS_KIND, agentDir) },
      )
      linked.addAll(outcome.linked)
      skipped.addAll(outcome.skipped)
    }
    return NativeAgentLinkOutcome(linked, skipped)
  }

  fun unlinkClaudeAgents(request: NativeAgentLinkRequest): List<Path> = unlinkProviderAgents(
    provider = NativeAgentProvider.Claude,
    request = request,
  )

  fun linkCodexAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Codex,
    request = request,
    detectTarget = { detectCodexAgentsTarget(it) },
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

  fun linkOpencodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Opencode,
    request = request,
    detectTarget = { detectOpencodeAgentsTarget(it) },
  )

  fun unlinkOpencodeAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Opencode, request = request)
    return uninstallOpencodeAgentMarkdown(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) +
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

  fun linkZcodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Zcode,
    request = request,
    detectTarget = { resolvedHome ->
      val targetPath = NativeAgentProvider.Zcode.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".zcode"))) {
        AgentTarget(ZCODE_AGENTS_KIND, targetPath)
      } else {
        null
      }
    },
  )

  fun unlinkZcodeAgents(request: NativeAgentLinkRequest): List<Path> = unlinkProviderAgents(
    provider = NativeAgentProvider.Zcode,
    request = request,
  )

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
    val cacheRoot = request.overrides.installCacheRoot?.toAbsolutePath()?.normalize()
      ?: NativeAgentOperations.installCacheRoot(resolvedHome, request.platformPacksRoot, request.skillsRoot)
    val rollback = ProviderReconciliationSnapshot.capture(resolvedHome, provider, cacheRoot)
    return try {
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
    val linkedPaths = linked.toSet()
    val desired = generated.artifacts.flatMap { artifact ->
      provider.homeAgentDirs(resolvedHome).mapNotNull { agentDir ->
        val installedPath = agentDir.resolve(artifact.path.fileName)
        if (installedPath !in linkedPaths && !Files.isSymbolicLink(installedPath)) return@mapNotNull null
        NativeAgentLinkInventoryEntry(
          logicalName = artifact.logicalName,
          provider = provider.name.lowercase(),
          installedPath = installedPath,
          cacheTargetPath = artifact.path,
          contentDigest = artifact.contentDigest,
          sourceRoot = validationRoot,
        )
      }
    }
    desired.forEach(::verifyInstalledNativeAgent)
    NativeAgentLinkInventory.reconcile(
      home = resolvedHome,
      provider = provider.name.lowercase(),
      desired = desired,
      managedRoots = managedRoots,
    )
    NativeAgentLinkOutcome(linked, skipped)
    } catch (error: Throwable) {
      rollback.restore()
      throw error
    }
  }

  private fun verifyInstalledNativeAgent(entry: NativeAgentLinkInventoryEntry) {
    val installed = entry.installedPath
    val repair = "skill-bill install apply"
    fun fail(reason: String, cause: Throwable? = null): Nothing = throw MissingInstalledNativeAgentError(
      logicalName = entry.logicalName,
      provider = entry.provider,
      expectedPath = installed.toString(),
      reason = reason,
      repairCommand = repair,
      cause = cause,
    )
    if (!Files.isSymbolicLink(installed)) fail("managed link is missing")
    val resolved = runCatching { installed.toRealPath() }
      .getOrElse { fail("managed link is dangling or unreadable", it) }
    if (resolved != entry.cacheTargetPath.toRealPath()) fail("managed link resolves outside the current cache target")
    if (!Files.isReadable(resolved)) fail("rendered artifact is unreadable")
    if (parseEmbeddedLogicalName(resolved, entry.provider) != entry.logicalName) {
      fail("rendered artifact logical name does not match the launch worker")
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(resolved))
      .joinToString("") { byte -> "%02x".format(byte) }
    if (digest != entry.contentDigest) fail("rendered artifact content digest is stale")
  }

  private fun parseEmbeddedLogicalName(path: Path, provider: String): String? {
    val text = Files.readString(path)
    val pattern = if (provider == "codex") {
      Regex("(?m)^name\\s*=\\s*\\\"([^\\\"]+)\\\"")
    } else {
      Regex("(?m)^name:\\s*['\\\"]?([^'\\\"\\r\\n]+)")
    }
    return pattern.find(text)?.groupValues?.get(1)?.trim()
  }

  private data class ProviderReconciliationSnapshot(
    val links: Map<Path, Path>,
    val cacheFiles: Map<Path, ByteArray>,
    val cacheRoot: Path,
    val inventoryPath: Path,
    val inventoryBytes: ByteArray?,
  ) {
    fun restore() {
      links.keys.map { it.parent }.distinct().forEach { directory ->
        if (Files.isDirectory(directory)) Files.list(directory).use { paths ->
          paths.iterator().asSequence().filter(Files::isSymbolicLink).forEach(Files::deleteIfExists)
        }
      }
      links.forEach { (link, rawTarget) ->
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, rawTarget)
      }
      if (Files.isDirectory(cacheRoot)) Files.walk(cacheRoot).use { paths ->
        paths.iterator().asSequence().sortedByDescending { it.nameCount }
          .filter { it != cacheRoot }.forEach(Files::deleteIfExists)
      }
      cacheFiles.forEach { (path, bytes) ->
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
      }
      if (inventoryBytes == null) Files.deleteIfExists(inventoryPath) else {
        Files.createDirectories(inventoryPath.parent)
        Files.write(inventoryPath, inventoryBytes)
      }
    }

    companion object {
      fun capture(home: Path, provider: NativeAgentProvider, cacheRoot: Path): ProviderReconciliationSnapshot {
        val links = provider.homeAgentDirs(home).flatMap { directory ->
          if (!Files.isDirectory(directory)) emptyList() else Files.list(directory).use { paths ->
            paths.iterator().asSequence().filter(Files::isSymbolicLink)
              .associateWith(Files::readSymbolicLink).entries.toList()
          }
        }.associate { it.key to it.value }
        val cacheFiles = if (!Files.isDirectory(cacheRoot)) emptyMap() else Files.walk(cacheRoot).use { paths ->
          paths.iterator().asSequence().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .associateWith(Files::readAllBytes)
        }
        val inventory = home.resolve(".skill-bill/native-agent-link-inventory.json")
        return ProviderReconciliationSnapshot(
          links, cacheFiles, cacheRoot, inventory,
          inventory.takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes),
        )
      }
    }
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
        overrides = NativeAgentInstallRenderOverrides(
          cacheRoot = request.overrides.installCacheRoot,
          sourceRoots = request.overrides.sourceRoots,
        ),
      ),
    )
    val legacyGenerated = request.overrides.legacyManagedRoot
      ?.takeIf { legacyRoot -> legacyRoot != generated.cacheRoot }
      ?.let { legacyRoot ->
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = request.platformPacksRoot,
            skillsRoot = request.skillsRoot,
            selectedPlatforms = request.selectedPlatforms,
            provider = provider,
            home = resolvedHome,
            overrides = NativeAgentInstallRenderOverrides(
              cacheRoot = legacyRoot,
              sourceRoots = request.overrides.sourceRoots,
            ),
          ),
        )
      }
    return uninstallNativeAgentFiles(
      (generated.generatedFiles + legacyGenerated?.generatedFiles.orEmpty()).distinct(),
      provider.homeAgentDirs(resolvedHome),
    )
  }
}
