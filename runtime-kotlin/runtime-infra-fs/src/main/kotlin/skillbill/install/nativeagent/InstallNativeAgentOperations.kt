package skillbill.install.nativeagent

import skillbill.error.MissingInstalledNativeAgentError
import skillbill.install.model.AgentTarget
import skillbill.install.plan.CLAUDE_AGENTS_KIND
import skillbill.install.plan.JUNIE_AGENTS_KIND
import skillbill.install.plan.ZCODE_AGENTS_KIND
import skillbill.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.validation.validateNativeAgentArtifactsForInstall
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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

  fun linkOpencodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Opencode,
    request = request,
    detectTargets = { home ->
      NativeAgentProvider.Opencode.activeHomeAgentDirs(home).map { AgentTarget("opencode-agents", it) }
    },
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

  fun linkZcodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome = linkProviderAgents(
    provider = NativeAgentProvider.Zcode,
    request = request,
    detectTargets = { resolvedHome ->
      val targetPath = NativeAgentProvider.Zcode.homeAgentDirs(resolvedHome).first()
      if (Files.exists(targetPath) || Files.exists(resolvedHome.resolve(".zcode"))) {
        listOf(AgentTarget(ZCODE_AGENTS_KIND, targetPath))
      } else {
        emptyList()
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
    detectTargets: (Path) -> List<AgentTarget>,
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
    val targets = detectTargets(resolvedHome)
    if (targets.isEmpty()) return NativeAgentLinkOutcome(emptyList(), emptyList())
    val cacheRoot = request.overrides.installCacheRoot?.toAbsolutePath()?.normalize()
      ?: NativeAgentOperations.installCacheRoot(resolvedHome, request.platformPacksRoot, request.skillsRoot)
    val rollback = ProviderReconciliationSnapshot.capture(
      resolvedHome,
      provider,
      cacheRoot,
      listOfNotNull(cacheRoot, request.overrides.legacyManagedRoot),
    )
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
      targets.forEach { target ->
        generated.generatedFiles.forEach { file ->
          when (val result = installNativeAgentFile(file, target, managedSourceRoots = managedRoots)) {
            is InstallNativeAgentResult.Linked -> linked.add(result.link)
            is InstallNativeAgentResult.Skipped -> skipped.add(NativeAgentSkippedLink(result.link, result.reason))
          }
        }
      }
      val linkedPaths = linked.toSet()
      val desired = generated.artifacts.flatMap { artifact ->
        targets.mapNotNull { target ->
          val agentDir = target.path
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
      runCatching { rollback.restore() }.exceptionOrNull()?.let(error::addSuppressed)
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
    val providerDirectories: Map<Path, Boolean>,
    val cacheEntries: Map<Path, FileSnapshot>,
    val cacheRoot: Path,
    val cacheRootExisted: Boolean,
    val inventoryPath: Path,
    val inventory: FileSnapshot?,
    val managedRoots: List<Path>,
  ) {
    fun restore() {
      restoreProviderLinks()
      restoreCache()
      restoreInventory()
      removeCreatedDirectories()
    }

    private fun restoreProviderLinks() {
      providerDirectories.keys.forEach { directory ->
        if (Files.isDirectory(directory)) {
          Files.list(directory).use { paths ->
            paths.iterator().asSequence().filter(Files::isSymbolicLink).filter { link ->
              val raw = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return@filter false
              val resolved = link.parent.resolve(raw).toAbsolutePath().normalize()
              managedRoots.any { resolved.startsWith(it.toAbsolutePath().normalize()) }
            }.forEach(Files::deleteIfExists)
          }
        }
      }
      links.forEach { (link, rawTarget) ->
        Files.createDirectories(link.parent)
        val currentTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull()
        if (currentTarget != rawTarget) {
          Files.deleteIfExists(link)
          Files.createSymbolicLink(link, rawTarget)
        }
      }
    }

    private fun restoreCache() {
      if (Files.isDirectory(cacheRoot)) {
        Files.walk(cacheRoot).use { paths ->
          paths.iterator().asSequence().sortedByDescending { it.nameCount }
            .filter { it != cacheRoot }.forEach(Files::deleteIfExists)
        }
      }
      cacheEntries.entries.sortedBy { it.key.nameCount }.forEach { (path, snapshot) -> snapshot.restore(path) }
    }

    private fun restoreInventory() {
      if (inventory == null) {
        Files.deleteIfExists(inventoryPath)
      } else {
        Files.createDirectories(inventoryPath.parent)
        inventory.restore(inventoryPath)
      }
    }

    private fun removeCreatedDirectories() {
      if (!cacheRootExisted && Files.isDirectory(cacheRoot) && isEmptyDirectory(cacheRoot)) {
        Files.deleteIfExists(cacheRoot)
      }
      providerDirectories.filterValues { existed -> !existed }.keys
        .sortedByDescending { it.nameCount }
        .forEach { path ->
          if (Files.isDirectory(path) && isEmptyDirectory(path)) Files.deleteIfExists(path)
        }
    }

    companion object {
      fun capture(
        home: Path,
        provider: NativeAgentProvider,
        cacheRoot: Path,
        managedRoots: List<Path>,
      ): ProviderReconciliationSnapshot {
        val providerDirectories = provider.homeAgentDirs(home).associateWith { Files.isDirectory(it) }
        val links = providerDirectories.keys.flatMap { directory ->
          if (!Files.isDirectory(directory)) {
            emptyList()
          } else {
            Files.list(directory).use { paths ->
              paths.iterator().asSequence().filter(Files::isSymbolicLink)
                .associateWith(Files::readSymbolicLink).entries.toList()
            }
          }
        }.associate { it.key to it.value }
        val cacheEntries = if (!Files.isDirectory(cacheRoot)) {
          emptyMap()
        } else {
          Files.walk(cacheRoot).use { paths ->
            paths.iterator().asSequence().filter { it != cacheRoot }
              .associateWith(FileSnapshot::capture)
          }
        }
        val inventory = home.resolve(".skill-bill/native-agent-link-inventory.json")
        return ProviderReconciliationSnapshot(
          links,
          providerDirectories,
          cacheEntries,
          cacheRoot,
          Files.isDirectory(cacheRoot),
          inventory,
          inventory.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let(FileSnapshot::capture),
          managedRoots.map { it.toAbsolutePath().normalize() },
        )
      }
    }
  }

  private data class FileSnapshot(
    val kind: FileKind,
    val bytes: ByteArray?,
    val rawTarget: Path?,
    val permissions: Set<PosixFilePermission>?,
  ) {
    fun restore(path: Path) {
      Files.deleteIfExists(path)
      when (kind) {
        FileKind.Directory -> Files.createDirectories(path)
        FileKind.Regular -> {
          Files.createDirectories(path.parent)
          Files.write(path, requireNotNull(bytes))
        }
        FileKind.SymbolicLink -> {
          Files.createDirectories(path.parent)
          Files.createSymbolicLink(path, requireNotNull(rawTarget))
        }
      }
      permissions?.let { runCatching { Files.setPosixFilePermissions(path, it) } }
    }

    companion object {
      fun capture(path: Path): FileSnapshot = FileSnapshot(
        kind = when {
          Files.isSymbolicLink(path) -> FileKind.SymbolicLink
          Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> FileKind.Directory
          else -> FileKind.Regular
        },
        bytes = path.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }?.let(Files::readAllBytes),
        rawTarget = path.takeIf(Files::isSymbolicLink)?.let(Files::readSymbolicLink),
        permissions = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull(),
      )
    }
  }

  private enum class FileKind { Directory, Regular, SymbolicLink }

  private fun isEmptyDirectory(path: Path): Boolean = Files.list(path).use { !it.findAny().isPresent }

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
