package skillbill.install.nativeagent

import skillbill.error.MissingInstalledNativeAgentError
import skillbill.install.model.AgentTarget
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

internal fun linkProviderAgents(
  provider: NativeAgentProvider,
  request: NativeAgentLinkRequest,
  detectTargets: (Path) -> List<AgentTarget>,
): NativeAgentLinkOutcome {
  val validationRoot = nativeAgentCompositionRepoRoot(request.platformPacksRoot, request.skillsRoot)
  request.overrides.sourceRoots
    ?.let { roots ->
      validateNativeAgentArtifactsForInstall(roots, validationRoot, installNativeAgentCompositionContext())
    }
    ?: validateNativeAgentArtifactsForInstall(
      request.platformPacksRoot,
      request.skillsRoot,
      request.selectedPlatforms,
      installNativeAgentCompositionContext(),
    )
  val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
  val targets = detectTargets(resolvedHome)
  if (targets.isEmpty()) return NativeAgentLinkOutcome(emptyList(), emptyList())
  val cacheRoot = request.overrides.installCacheRoot?.toAbsolutePath()?.normalize()
    ?: NativeAgentOperations.installCacheRoot(resolvedHome, request.platformPacksRoot, request.skillsRoot)
  val journal = ProviderMutationJournal()
  return linkProviderAgentsWithJournal(journal) {
    linkProviderAgentsBody(
      NativeAgentLinkProviderBodyArgs(
        provider = provider,
        request = request,
        targets = targets,
        resolvedHome = resolvedHome,
        cacheRoot = cacheRoot,
        validationRoot = validationRoot,
        journal = journal,
      ),
    )
  }
}

/**
 * Staged into a sibling directory and swapped in with two renames, rather than pruned and
 * recopied in place. The in-memory [ProviderMutationJournal] cannot roll back an abrupt process
 * death, and an in-place rewrite leaves a pack whose `platform.yaml` is deleted but not yet
 * recopied — which every reader loads as a corrupt pack. With the swap the only externally
 * visible states are the previous catalog, the new catalog, and briefly no catalog at all;
 * readers degrade on absence and never observe a partially written pack.
 */
internal fun publishInstalledReviewCatalog(
  platformPacksRoot: Path,
  selectedPlatforms: List<String>?,
  cacheRoot: Path,
  journal: ProviderMutationJournal,
) {
  val catalogParent = cacheRoot.resolve("review-catalog")
  val catalogRoot = catalogParent.resolve("platform-packs")
  val staging = catalogParent.resolve(".platform-packs.staging")
  val superseded = catalogParent.resolve(".platform-packs.superseded")

  journal.beforeMutation(catalogParent)
  Files.createDirectories(catalogParent)
  deleteRecursively(staging)
  deleteRecursively(superseded)
  journal.afterTemporaryCreation(staging)
  Files.createDirectories(staging)

  stageReviewCatalogPacks(platformPacksRoot, selectedPlatforms, staging)
  journalReviewCatalogSwap(catalogRoot, staging, journal)
  swapReviewCatalogIntoPlace(catalogRoot, staging, superseded)
}

internal fun deleteRecursively(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }
}

internal fun verifyInstalledNativeAgent(entry: NativeAgentLinkInventoryEntry) {
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

internal fun parseEmbeddedLogicalName(path: Path, provider: String): String? {
  val text = Files.readString(path)
  val pattern = if (provider == "codex") {
    Regex("(?m)^name\\s*=\\s*\\\"([^\\\"]+)\\\"")
  } else {
    Regex("(?m)^name:\\s*['\\\"]?([^'\\\"\\r\\n]+)")
  }
  return pattern.find(text)?.groupValues?.get(1)?.trim()
}

internal class ProviderMutationJournal {
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

internal fun isEmptyDirectory(path: Path): Boolean = Files.list(path).use { !it.findAny().isPresent }

internal fun unlinkProviderAgents(provider: NativeAgentProvider, request: NativeAgentLinkRequest): List<Path> {
  val resolvedHome = request.home ?: Path.of(System.getProperty("user.home"))
  val compositionContext = installNativeAgentCompositionContext()
  val generated = NativeAgentOperations.renderInstallArtifacts(
    NativeAgentInstallRenderRequest(
      platformPacksRoot = request.platformPacksRoot,
      skillsRoot = request.skillsRoot,
      selectedPlatforms = request.selectedPlatforms,
      provider = provider,
      home = resolvedHome,
      compositionContext = compositionContext,
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
          compositionContext = compositionContext,
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
