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
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
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
    val journal = ProviderMutationJournal()
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
            beforeMutation = journal::beforeMutation,
            afterTemporaryCreation = journal::afterTemporaryCreation,
          ),
        ),
      )
      val managedRoots = listOfNotNull(generated.cacheRoot, request.overrides.legacyManagedRoot)
      val linked = mutableListOf<Path>()
      val skipped = mutableListOf<NativeAgentSkippedLink>()
      val artifactsByPath = generated.artifacts.associateBy { it.path }
      targets.forEach { target ->
        generated.generatedFiles.forEach { file ->
          when (
            val result = installNativeAgentFile(
              file,
              target,
              managedSourceRoots = managedRoots,
              ownership = NativeAgentLinkOwnership(
                resolvedHome,
                provider,
                requireNotNull(artifactsByPath[file]).logicalName,
              ),
              beforeMutation = journal::beforeMutation,
            )
          ) {
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
        sourceRoot = validationRoot,
        beforeMutation = journal::beforeMutation,
        afterTemporaryCreation = journal::afterTemporaryCreation,
      )
      NativeAgentLinkOutcome(linked, skipped)
    } catch (error: Throwable) {
      journal.restore().forEach(error::addSuppressed)
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

  private class ProviderMutationJournal {
    private val entries = linkedMapOf<Path, FileSnapshot?>()

    fun beforeMutation(path: Path) {
      val normalized = path.toAbsolutePath().normalize()
      generateSequence(normalized.parent) { it.parent }
        .takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        .toList().asReversed().forEach(::record)
      record(normalized)
    }

    fun afterTemporaryCreation(path: Path) {
      entries.putIfAbsent(path.toAbsolutePath().normalize(), null)
    }

    private fun record(normalized: Path) {
      if (normalized !in entries) {
        entries[normalized] = normalized.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
          ?.let(FileSnapshot::capture)
      }
    }

    fun restore(): List<Throwable> {
      val failures = mutableListOf<Throwable>()
      entries.entries.toList().asReversed().forEach { (path, snapshot) ->
        runCatching {
          if (snapshot == null) {
            when {
              Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && isEmptyDirectory(path) -> Files.deleteIfExists(path)
              !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.deleteIfExists(path)
            }
          } else {
            snapshot.restore(path)
          }
        }.exceptionOrNull()?.let(failures::add)
      }
      return failures
    }
  }

  private data class FileSnapshot(
    val kind: FileKind,
    val bytes: ByteArray?,
    val rawTarget: Path?,
    val permissions: Set<PosixFilePermission>?,
    val dosAttributes: DosAttributes?,
  ) {
    fun restore(path: Path) {
      when (kind) {
        FileKind.Directory -> Files.createDirectories(path)
        FileKind.Regular -> {
          Files.deleteIfExists(path)
          Files.createDirectories(path.parent)
          Files.write(path, requireNotNull(bytes))
        }
        FileKind.SymbolicLink -> {
          Files.deleteIfExists(path)
          Files.createDirectories(path.parent)
          Files.createSymbolicLink(path, requireNotNull(rawTarget))
        }
      }
      permissions?.let { captured ->
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
          ?.setPermissions(captured)
      }
      dosAttributes?.restore(path)
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
        permissions = captureOptionalAttributes {
          Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()?.permissions()
        },
        dosAttributes = captureOptionalAttributes {
          Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()?.let { DosAttributes(it.isReadOnly, it.isHidden, it.isArchive, it.isSystem) }
        },
      )

      private fun <T> captureOptionalAttributes(read: () -> T?): T? = try {
        read()
      } catch (_: UnsupportedOperationException) {
        null
      } catch (error: FileSystemException) {
        if (error.reason.orEmpty().contains("not supported", ignoreCase = true) ||
          error.reason.orEmpty().contains("too many levels of symbolic links", ignoreCase = true)
        ) {
          null
        } else {
          throw error
        }
      }
    }
  }

  private data class DosAttributes(
    val readOnly: Boolean,
    val hidden: Boolean,
    val archive: Boolean,
    val system: Boolean,
  ) {
    fun restore(path: Path) {
      Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.apply {
        setReadOnly(readOnly)
        setHidden(hidden)
        setArchive(archive)
        setSystem(system)
      }
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
