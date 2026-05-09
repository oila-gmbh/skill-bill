package skillbill.nativeagent

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

object NativeAgentOperations {
  fun regenerate(
    repoRoot: Path,
    skillNames: List<String> = emptyList(),
    home: Path = Path.of(System.getProperty("user.home")),
    originalBytes: MutableMap<Path, ByteArray>? = null,
    createdPaths: MutableList<Path>? = null,
  ): NativeAgentRegenerationResult {
    val root = repoRoot.toAbsolutePath().normalize()
    val sources = discoverRepoNativeAgentSources(root)
      .filter { source -> skillNames.isEmpty() || source.parent.parent.name in skillNames }
    if (sources.isEmpty()) {
      return NativeAgentRegenerationResult(emptyList())
    }
    val cacheRoot = installCacheRoot(home, root.resolve("platform-packs"), root.resolve("skills"))
    val written = mutableListOf<Path>()
    val byProvider = NativeAgentProvider.entries.associateWith { provider ->
      sources.map { sourcePath ->
        val source = parseNativeAgentSource(sourcePath)
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

  fun renderInstallArtifacts(
    platformPacksRoot: Path,
    skillsRoot: Path?,
    selectedPlatforms: List<String>?,
    provider: NativeAgentProvider,
    home: Path,
  ): NativeAgentInstallRenderResult {
    validateNativeAgentArtifactsForInstall(platformPacksRoot, skillsRoot, selectedPlatforms)
    val cacheRoot = installCacheRoot(home, platformPacksRoot, skillsRoot)
    val providerRoot = cacheRoot.resolve(provider.directoryName)
    val sources = discoverNativeAgentSources(platformPacksRoot, skillsRoot, selectedPlatforms)
    val repoRoot = nativeAgentCompositionRepoRoot(platformPacksRoot, skillsRoot)
    val rendered = sources.map { sourcePath ->
      val source = parseNativeAgentSource(sourcePath)
      val composed = composeNativeAgentSource(repoRoot, source)
      RenderedAgent(
        targetName = "${composed.name}.${provider.extension}",
        contents = provider.render(composed).toByteArray(Charsets.UTF_8),
      )
    }
    val staging = Files.createTempDirectory("skill-bill-native-agent-render-")
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
      Files.move(
        staging.resolve(entry.targetName),
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
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

  internal fun discoverRepoNativeAgentSources(repoRoot: Path): List<Path> {
    val roots = listOf(repoRoot.resolve("skills"), repoRoot.resolve("platform-packs"))
    return roots.filter { it.isDirectory() }.flatMap { root ->
      Files.walk(root).use { stream ->
        stream
          .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
          .filter { file -> file.parent?.name == NATIVE_AGENT_SOURCE_DIR && file.fileName.toString().endsWith(".md") }
          .toList()
      }
    }.sortedBy { it.toString() }
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
