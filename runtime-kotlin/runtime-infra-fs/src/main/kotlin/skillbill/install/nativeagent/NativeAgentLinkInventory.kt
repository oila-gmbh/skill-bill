@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "LongParameterList")

package skillbill.install.nativeagent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import skillbill.contracts.nativeagent.NativeAgentLinkInventorySchemaPaths
import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class NativeAgentLinkInventoryEntry(
  val logicalName: String,
  val provider: String,
  val installedPath: Path,
  val cacheTargetPath: Path,
  val contentDigest: String,
  val sourceRoot: Path,
)

internal object NativeAgentLinkInventory {
  private val mapper = ObjectMapper()
  private val schema by lazy {
    val resource = requireNotNull(javaClass.getResourceAsStream(NativeAgentLinkInventorySchemaPaths.CLASSPATH_RESOURCE))
    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(YAMLMapper().readTree(resource))
  }

  fun reconcile(
    home: Path,
    provider: String,
    desired: List<NativeAgentLinkInventoryEntry>,
    managedRoots: List<Path>,
    sourceRoot: Path,
    beforeMutation: (Path) -> Unit = {},
    afterTemporaryCreation: (Path) -> Unit = {},
  ) {
    val path = inventoryPath(home)
    val trustedRoots = canonicalManagedCacheRoots(home, managedRoots)
    val previous = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      read(home, trustedRoots, sourceRoot)
    } else {
      val bootstrap = bootstrap(home, trustedRoots, sourceRoot)
      bootstrap.remove.forEach { removeIfStillManaged(it, home, trustedRoots, beforeMutation) }
      bootstrap.retain
    }
    val desiredPaths = desired.map { it.installedPath.normalize() }.toSet()
    previous.filter { it.provider == provider && it.installedPath.normalize() !in desiredPaths }.forEach { stale ->
      removeIfStillManaged(stale, home, trustedRoots, beforeMutation)
    }
    val retained = previous.filter { it.provider != provider }.filter { entry ->
      if (isSemanticallyValid(entry, home, trustedRoots)) {
        true
      } else {
        removeIfStillManaged(entry, home, trustedRoots, beforeMutation)
        false
      }
    }
    try {
      write(
        path,
        (retained + desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })),
        home,
        trustedRoots,
        beforeMutation,
        afterTemporaryCreation,
      )
    } catch (error: InvalidNativeAgentLinkInventorySchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory publication '$path': ${error.message}",
        error,
      )
    }
  }

  fun read(home: Path, managedRoots: List<Path>, sourceRoot: Path? = null): List<NativeAgentLinkInventoryEntry> {
    val path = inventoryPath(home)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return sourceRoot?.let { bootstrap(home, managedRoots, it).retain }.orEmpty()
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': inventory must be a regular file. Delete it and reinstall.",
      )
    }
    return decode(path, home, managedRoots)
  }

  private fun removeIfStillManaged(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
  ) {
    val link = entry.installedPath
    val provider = provider(entry.provider)
    if (link.fileName.toString() != provider.fileName(entry.logicalName)) return
    if (!Files.isSymbolicLink(link)) return
    val rawTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots)) {
      beforeMutation(link)
      Files.deleteIfExists(link)
    }
  }

  private fun decode(path: Path, home: Path, managedRoots: List<Path>): List<NativeAgentLinkInventoryEntry> {
    return try {
      require(Files.size(path) <= MAX_BYTES) { "inventory exceeds $MAX_BYTES bytes" }
      val root = mapper.readTree(path.toFile())
      val schemaErrors = schema.validate(root)
      require(schemaErrors.isEmpty()) { schemaErrors.joinToString("; ") { it.message } }
      val entries = root["entries"]?.elements()?.asSequence()?.map { node ->
        NativeAgentLinkInventoryEntry(
          logicalName = node.requiredText("logical_name"),
          provider = node.requiredText("provider"),
          installedPath = Path.of(node.requiredText("installed_path")),
          cacheTargetPath = Path.of(node.requiredText("cache_target_path")),
          contentDigest = node.requiredText("content_digest"),
          sourceRoot = Path.of(node.requiredText("source_root")),
        )
      }?.toList() ?: error("entries is required")
      require(entries.map { it.provider to it.installedPath.normalize() }.distinct().size == entries.size) {
        "duplicate provider/installed_path entry"
      }
      require(
        entries.groupBy { Triple(it.provider, it.installedPath.parent.normalize(), it.logicalName) }
          .values.none { it.size > 1 },
      ) { "duplicate provider/directory/logical_name entry" }
      entries.forEach { entry ->
        require(entry.provider in PROVIDERS) { "unsupported provider '${entry.provider}'" }
        require(entry.contentDigest.matches(Regex("[0-9a-f]{$DIGEST_HEX_LENGTH}"))) { "invalid content_digest" }
        require(entry.installedPath.isAbsolute) { "installed_path must be absolute" }
        require(entry.cacheTargetPath.isAbsolute) { "cache_target_path must be absolute" }
        require(entry.sourceRoot.isAbsolute && entry.sourceRoot.toString().length <= MAX_SOURCE_ROOT_LENGTH) {
          "source_root must be an absolute bounded path"
        }
        require(entry.sourceRoot == entry.sourceRoot.normalize()) { "source_root must be normalized" }
        require(entry.installedPath == entry.installedPath.normalize()) { "installed_path must be normalized" }
        require(entry.cacheTargetPath == entry.cacheTargetPath.normalize()) { "cache_target_path must be normalized" }
        require(LOGICAL_NAME.matches(entry.logicalName)) { "logical_name must be a single filename stem" }
        val provider = provider(entry.provider)
        val allowedDirs = provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }
        require(entry.installedPath.parent in allowedDirs) { "installed_path is outside provider directory" }
        require(entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName)) {
          "installed_path does not match provider/logical_name identity"
        }
        require(
          isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, entry.cacheTargetPath, managedRoots),
        ) {
          "cache_target_path does not match a trusted provider artifact"
        }
      }
      entries
    } catch (error: Exception) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
    }
  }

  private data class BootstrapPlan(
    val retain: List<NativeAgentLinkInventoryEntry>,
    val remove: List<NativeAgentLinkInventoryEntry>,
  )

  private fun bootstrap(home: Path, managedRoots: List<Path>, sourceRoot: Path): BootstrapPlan {
    val retain = mutableListOf<NativeAgentLinkInventoryEntry>()
    val remove = mutableListOf<NativeAgentLinkInventoryEntry>()
    skillbill.nativeagent.rendering.NativeAgentProvider.entries.flatMap { provider ->
      provider.homeAgentDirs(home).flatMap { directory ->
        if (!Files.isDirectory(directory)) return@flatMap emptyList()
        Files.list(directory).use { paths ->
          paths.iterator().asSequence().filter(Files::isSymbolicLink).mapNotNull { link: Path ->
            val raw = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return@mapNotNull null
            val resolved = link.parent.resolve(raw).toAbsolutePath().normalize()
            val logicalName = link.fileName.toString().removeSuffix(".${provider.extension}")
            if (!LOGICAL_NAME.matches(logicalName) || link.fileName.toString() != provider.fileName(logicalName)) {
              return@mapNotNull null
            }
            if (!isCanonicalNativeAgentArtifactTarget(home, provider, logicalName, resolved, managedRoots)) {
              return@mapNotNull null
            }
            val entry = NativeAgentLinkInventoryEntry(
              logicalName,
              provider.name.lowercase(),
              link.toAbsolutePath().normalize(),
              resolved,
              contentDigest = if (Files.isRegularFile(resolved) && Files.isReadable(resolved)) {
                runCatching { sha256(Files.readAllBytes(resolved)) }.getOrDefault(EMPTY_DIGEST)
              } else {
                EMPTY_DIGEST
              },
              sourceRoot = sourceRoot.toAbsolutePath().normalize(),
            )
            if (isSemanticallyValid(entry, home, managedRoots)) retain += entry else remove += entry
            entry
          }.toList()
        }
      }
    }
    return BootstrapPlan(retain, remove)
  }

  private fun write(
    path: Path,
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
    afterTemporaryCreation: (Path) -> Unit,
  ) {
    path.parent?.let { parent ->
      journalMissingAncestors(parent, beforeMutation)
      Files.createDirectories(parent)
    }
    val root = mapper.createObjectNode().put("contract_version", NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION)
    val array = root.putArray("entries")
    entries.forEach { entry ->
      array.addObject()
        .put("logical_name", entry.logicalName)
        .put("provider", entry.provider)
        .put("installed_path", entry.installedPath.toAbsolutePath().normalize().toString())
        .put("cache_target_path", entry.cacheTargetPath.toAbsolutePath().normalize().toString())
        .put("content_digest", entry.contentDigest)
        .put("source_root", entry.sourceRoot.toAbsolutePath().normalize().toString())
    }
    val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)
    require(bytes.size <= MAX_BYTES) { "native-agent link inventory exceeds $MAX_BYTES bytes" }
    val schemaErrors = schema.validate(mapper.readTree(bytes))
    require(schemaErrors.isEmpty()) { schemaErrors.joinToString("; ") { it.message } }
    validateSemanticEntries(entries, home, managedRoots)
    val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
    afterTemporaryCreation(temporary)
    var initiatingFailure: Exception? = null
    try {
      Files.write(temporary, bytes)
      beforeMutation(path)
      try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
      }
    } catch (error: Exception) {
      initiatingFailure = error
    }
    val cleanupFailure = runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()
    cleanupFailure?.let { initiatingFailure?.addSuppressed(it) }
    initiatingFailure?.let { throw it }
    cleanupFailure?.let { throw it }
  }

  private fun validateSemanticEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
  ) {
    require(entries.map { it.provider to it.installedPath.normalize() }.distinct().size == entries.size) {
      "duplicate provider/installed_path entry"
    }
    val logicalIdentities = entries.map {
      Triple(it.provider, it.installedPath.parent.normalize(), it.logicalName)
    }
    require(logicalIdentities.distinct().size == entries.size) {
      "duplicate provider/directory/logical_name entry"
    }
    entries.forEach { entry ->
      val provider = provider(entry.provider)
      require(entry.sourceRoot.isAbsolute && entry.sourceRoot == entry.sourceRoot.normalize()) {
        "invalid source_root"
      }
      require(entry.installedPath.isAbsolute && entry.installedPath == entry.installedPath.normalize()) {
        "invalid installed_path"
      }
      require(entry.cacheTargetPath.isAbsolute && entry.cacheTargetPath == entry.cacheTargetPath.normalize()) {
        "invalid cache_target_path"
      }
      require(entry.installedPath.parent in provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }) {
        "installed_path is outside provider directory"
      }
      require(entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName)) {
        "invalid installed identity"
      }
      require(
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, entry.cacheTargetPath, managedRoots),
      ) {
        "cache_target_path does not match a trusted provider artifact"
      }
      require(entry.contentDigest.matches(Regex("[0-9a-f]{$DIGEST_HEX_LENGTH}"))) { "invalid content_digest" }
      require(isSemanticallyValid(entry, home, managedRoots)) { "installed artifact is not semantically valid" }
    }
  }

  private fun isSemanticallyValid(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
  ): Boolean {
    return runCatching {
      val provider = provider(entry.provider)
      val raw = Files.readSymbolicLink(entry.installedPath)
      val resolved = entry.installedPath.parent.resolve(raw).toAbsolutePath().normalize()
      entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName) &&
        Files.isSymbolicLink(entry.installedPath) &&
        resolved == entry.cacheTargetPath &&
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots) &&
        Files.isRegularFile(resolved) &&
        Files.isReadable(resolved) &&
        parseEmbeddedLogicalName(resolved, entry.provider) == entry.logicalName &&
        sha256(Files.readAllBytes(resolved)) == entry.contentDigest
    }.getOrDefault(false)
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

  private fun journalMissingAncestors(path: Path, beforeMutation: (Path) -> Unit) {
    val missing = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
      .takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.toList().asReversed()
    missing.forEach(beforeMutation)
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

  private fun canonicalManagedCacheRoots(home: Path, managedRoots: List<Path>): List<Path> {
    val normalizedHome = home.toAbsolutePath().normalize()
    val installedGenerations = generationChildren(
      normalizedHome.resolve(".skill-bill/installed-skills"),
      prefix = "native-agents-",
    )
    val legacyGenerations = generationChildren(normalizedHome.resolve(".skill-bill/native-agents"))
    return (managedRoots.map { it.toAbsolutePath().normalize() } + installedGenerations + legacyGenerations).distinct()
  }

  private fun generationChildren(parent: Path, prefix: String = ""): List<Path> {
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.list(parent).use { children ->
      children.filter { child ->
        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
          child.fileName.toString().removePrefix(prefix).let { leaf ->
            child.fileName.toString().startsWith(prefix) && CACHE_GENERATION.matches(leaf)
          }
      }.map { it.toAbsolutePath().normalize() }.toList()
    }
  }

  private fun com.fasterxml.jackson.databind.JsonNode.requiredText(field: String): String =
    get(field)?.asText()?.takeIf(String::isNotBlank) ?: error("$field is required")

  private fun inventoryPath(home: Path): Path = home.resolve(".skill-bill/native-agent-link-inventory.json")
    .toAbsolutePath().normalize()

  private const val MAX_BYTES = 1024 * 1024L
  private const val DIGEST_HEX_LENGTH = 64
  private val EMPTY_DIGEST = "0".repeat(DIGEST_HEX_LENGTH)
  private const val MAX_SOURCE_ROOT_LENGTH = 4096
  private val LOGICAL_NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
  internal val CACHE_GENERATION = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,31})-)?[0-9a-f]{16}")
  private val PROVIDERS = setOf("claude", "codex", "opencode", "junie", "zcode")
  private fun provider(id: String) = skillbill.nativeagent.rendering.NativeAgentProvider.entries
    .single { it.name.lowercase() == id }
}

@Suppress("ReturnCount")
internal fun isCanonicalNativeAgentArtifactTarget(
  home: Path,
  provider: skillbill.nativeagent.rendering.NativeAgentProvider,
  logicalName: String,
  target: Path,
  currentRoots: List<Path>,
): Boolean {
  val normalized = target.toAbsolutePath().normalize()
  val root = normalized.parent?.parent ?: return false
  if (normalized != provider.cacheArtifactPath(root, logicalName)) return false
  if (root in currentRoots.map { it.toAbsolutePath().normalize() }) return true
  val normalizedHome = home.toAbsolutePath().normalize()
  val parent = root.parent ?: return false
  val leaf = root.fileName.toString()
  return when (parent) {
    normalizedHome.resolve(".skill-bill/installed-skills") ->
      leaf.startsWith("native-agents-") &&
        NativeAgentLinkInventory.CACHE_GENERATION.matches(leaf.removePrefix("native-agents-"))
    normalizedHome.resolve(".skill-bill/native-agents") ->
      LEGACY_CACHE_GENERATION.matches(leaf)
    else -> isLegacyGeneratedRepositoryArtifactTarget(root, logicalName)
  }
}

private fun isLegacyGeneratedRepositoryArtifactTarget(root: Path, logicalName: String): Boolean {
  val owner = root.fileName.toString()
  val authoredSurface = root.parent
  val matchesOwner = logicalName == owner || logicalName.startsWith("$owner-")
  val isBaseSkill = authoredSurface?.fileName?.toString() == "skills"
  val isPlatformReview = authoredSurface?.fileName?.toString() == "code-review" &&
    authoredSurface.parent?.parent?.fileName?.toString() == "platform-packs"
  return matchesOwner && (isBaseSkill || isPlatformReview)
}

private val LEGACY_CACHE_GENERATION = Regex("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?")
