package skillbill.nativeagent.rendering

import skillbill.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntriesInRoots
import skillbill.nativeagent.validation.validateNativeAgentArtifactsForInstall
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val NATIVE_AGENT_CACHE_KEY_BYTES = 8
private const val NATIVE_AGENT_SLUG_MAX_CHARS = 32

data class NativeAgentRegenerationResult(
  val regeneratedFiles: List<Path>,
)

data class NativeAgentInstallRenderResult(
  val generatedFiles: List<Path>,
  val cacheRoot: Path,
)

data class NativeAgentInstallRenderOverrides(
  val cacheRoot: Path? = null,
  val sourceRoots: List<Path>? = null,
)

data class NativeAgentInstallRenderRequest(
  val platformPacksRoot: Path,
  val skillsRoot: Path?,
  val selectedPlatforms: List<String>?,
  val provider: NativeAgentProvider,
  val home: Path,
  val overrides: NativeAgentInstallRenderOverrides = NativeAgentInstallRenderOverrides(),
)

object NativeAgentOperations {
  fun regenerate(
    repoRoot: Path,
    skillNames: List<String> = emptyList(),
    home: Path = Path.of(System.getProperty("user.home")),
    originalBytes: MutableMap<Path, ByteArray>? = null,
    createdPaths: MutableList<Path>? = null,
  ): NativeAgentRegenerationResult {
    val root = repoRoot.toAbsolutePath().normalize()
    val selectedSkillNames = skillNames.toSet()
    val sourceFiles = discoverRepoNativeAgentSourceFiles(root)
      .filter { sourcePath ->
        selectedSkillNames.isEmpty() || sourcePath.parent?.parent?.name in selectedSkillNames
      }
    val sources = sourceFiles.flatMap(::parseNativeAgentSourceFile)
    if (sources.isEmpty()) {
      return NativeAgentRegenerationResult(emptyList())
    }
    val cacheRoot = installCacheRoot(home, root.resolve("platform-packs"), root.resolve("skills"))
    val written = mutableListOf<Path>()
    val byProvider = NativeAgentProvider.entries.associateWith { provider ->
      sources.map { source ->
        val composed = composeNativeAgentSource(root, source)
        RegenerationEntry(
          target = cacheRoot.resolve(provider.directoryName).resolve("${composed.name}.${provider.extension}"),
          contents = provider.render(composed).toByteArray(Charsets.UTF_8),
        )
      }
    }
    byProvider.forEach { (provider, entries) ->
      val providerRoot = cacheRoot.resolve(provider.directoryName)
      Files.createDirectories(providerRoot)
      entries.forEach { entry ->
        val existed = Files.exists(entry.target)
        val current = if (existed) Files.readAllBytes(entry.target) else null
        if (current != null && current.contentEquals(entry.contents)) {
          return@forEach
        }
        if (existed && originalBytes != null && entry.target !in originalBytes) {
          originalBytes[entry.target] = current ?: ByteArray(0)
        }
        Files.write(entry.target, entry.contents)
        if (!existed) {
          createdPaths?.add(entry.target)
        }
        written.add(entry.target)
      }
    }
    return NativeAgentRegenerationResult(written.sortedBy { it.toString() })
  }

  private data class RegenerationEntry(val target: Path, val contents: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
  }

  fun renderInstallArtifacts(request: NativeAgentInstallRenderRequest): NativeAgentInstallRenderResult {
    val platformPacksRoot = request.platformPacksRoot
    val skillsRoot = request.skillsRoot
    val selectedPlatforms = request.selectedPlatforms
    val provider = request.provider
    val repoRoot = nativeAgentCompositionRepoRoot(platformPacksRoot, skillsRoot)
    if (request.overrides.sourceRoots == null) {
      validateNativeAgentArtifactsForInstall(platformPacksRoot, skillsRoot, selectedPlatforms)
    } else {
      validateNativeAgentArtifactsForInstall(request.overrides.sourceRoots, repoRoot)
    }
    val cacheRoot = request.overrides.cacheRoot?.toAbsolutePath()?.normalize()
      ?: installCacheRoot(request.home, platformPacksRoot, skillsRoot)
    val providerRoot = cacheRoot.resolve(provider.directoryName)
    val sources = request.overrides.sourceRoots
      ?.let(::discoverNativeAgentSourceEntriesInRoots)
      ?: discoverNativeAgentSourceEntries(platformPacksRoot, skillsRoot, selectedPlatforms)
    val rendered = sources.map { source ->
      val composed = composeNativeAgentSource(repoRoot, source)
      RenderedAgent(
        targetName = "${composed.name}.${provider.extension}",
        contents = provider.render(composed).toByteArray(Charsets.UTF_8),
      )
    }
    Files.createDirectories(providerRoot)
    val staging = Files.createTempDirectory(providerRoot, ".skill-bill-native-agent-render-")
    return try {
      rendered.forEach { entry ->
        Files.write(staging.resolve(entry.targetName), entry.contents)
      }
      val generated = promoteStagedRenders(providerRoot, staging, rendered)
      NativeAgentInstallRenderResult(
        generatedFiles = generated.sortedBy { it.toString() },
        cacheRoot = cacheRoot,
      )
    } finally {
      deleteDirectoryRecursively(staging)
    }
  }

  private data class RenderedAgent(val targetName: String, val contents: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
  }

  private fun promoteStagedRenders(providerRoot: Path, staging: Path, rendered: List<RenderedAgent>): List<Path> {
    Files.createDirectories(providerRoot)
    val expectedNames = rendered.map { it.targetName }.toSet()
    pruneOrphanArtifacts(providerRoot, expectedNames)
    return rendered.map { entry ->
      val target = providerRoot.resolve(entry.targetName)
      val source = staging.resolve(entry.targetName)
      try {
        Files.move(
          source,
          target,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(
          source,
          target,
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
      target
    }
  }

  private fun pruneOrphanArtifacts(providerRoot: Path, expectedNames: Set<String>) {
    if (!Files.isDirectory(providerRoot)) {
      return
    }
    Files.list(providerRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
        .filter { path -> path.fileName.toString() !in expectedNames }
        .forEach(Files::deleteIfExists)
    }
  }

  private fun deleteDirectoryRecursively(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return
    }
    Files.walk(root).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  fun installCacheRoot(home: Path, platformPacksRoot: Path, skillsRoot: Path?): Path {
    val hash = stableRepoKey(platformPacksRoot, skillsRoot)
    val slug = repoSlug(platformPacksRoot)
    val leaf = if (slug.isEmpty()) hash else "$slug-$hash"
    return home.toAbsolutePath().normalize().resolve(".skill-bill/native-agents/$leaf")
  }

  private fun repoSlug(platformPacksRoot: Path): String {
    val raw = platformPacksRoot.toAbsolutePath().normalize().parent?.fileName?.toString().orEmpty()
    if (raw.isEmpty()) {
      return ""
    }
    val collapsed = raw.lowercase()
      .replace(Regex("[^a-z0-9-]+"), "-")
      .trim('-')
    return collapsed.take(NATIVE_AGENT_SLUG_MAX_CHARS)
  }

  private fun stableRepoKey(platformPacksRoot: Path, skillsRoot: Path?): String {
    val input = listOfNotNull(
      platformPacksRoot.toAbsolutePath().normalize().toString(),
      skillsRoot?.toAbsolutePath()?.normalize()?.toString(),
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(NATIVE_AGENT_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
  }
}

internal fun discoverRepoNativeAgentSources(repoRoot: Path): List<Path> {
  return discoverRepoNativeAgentSourceFiles(repoRoot)
}

internal fun discoverRepoNativeAgentSourceEntries(repoRoot: Path): List<NativeAgentSource> {
  return discoverRepoNativeAgentSourceFiles(repoRoot).flatMap(::parseNativeAgentSourceFile)
}

internal fun discoverRepoNativeAgentSourceFiles(repoRoot: Path): List<Path> {
  val roots = listOf(repoRoot.resolve("skills"), repoRoot.resolve("platform-packs"))
  return roots.filter { it.isDirectory() }.flatMap { root ->
    Files.walk(root).use { stream ->
      stream
        .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
        .filter { file ->
          file.parent?.name == NATIVE_AGENT_SOURCE_DIR &&
            (file.fileName.toString().endsWith(".md") || file.fileName.toString() == NATIVE_AGENT_BUNDLE_FILE)
        }
        .toList()
    }
  }.sortedBy { it.toString() }
}
