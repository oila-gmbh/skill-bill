@file:Suppress("TooGenericExceptionCaught")

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

  fun reconcile(home: Path, provider: String, desired: List<NativeAgentLinkInventoryEntry>, managedRoots: List<Path>) {
    val path = inventoryPath(home)
    val trustedRoots = managedRoots + listOf(home.resolve(".skill-bill/installed-skills"))
    val previous = read(home, trustedRoots)
    val desiredPaths = desired.map { it.installedPath.normalize() }.toSet()
    previous.filter { it.provider == provider && it.installedPath.normalize() !in desiredPaths }.forEach { stale ->
      removeIfStillManaged(stale, trustedRoots)
    }
    val retained = previous.filter { it.provider != provider }
    try {
      write(path, (retained + desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })))
    } catch (error: InvalidNativeAgentLinkInventorySchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory publication '$path': ${error.message}",
        error,
      )
    }
  }

  fun read(home: Path, managedRoots: List<Path>): List<NativeAgentLinkInventoryEntry> {
    val path = inventoryPath(home)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return bootstrap(home, managedRoots)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': inventory must be a regular file. Delete it and reinstall.",
      )
    }
    return decode(path, home, managedRoots)
  }

  private fun removeIfStillManaged(entry: NativeAgentLinkInventoryEntry, managedRoots: List<Path>) {
    val link = entry.installedPath
    val provider = provider(entry.provider)
    if (link.fileName.toString() != provider.fileName(entry.logicalName)) return
    if (!Files.isSymbolicLink(link)) return
    val rawTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (managedRoots.any { root -> resolved == provider.cacheArtifactPath(root, entry.logicalName) }) {
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
        require(Files.isDirectory(entry.sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
          "source_root must identify a regular repository directory"
        }
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
          managedRoots.any { root -> entry.cacheTargetPath == provider.cacheArtifactPath(root, entry.logicalName) },
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

  private fun bootstrap(home: Path, managedRoots: List<Path>): List<NativeAgentLinkInventoryEntry> =
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
            if (managedRoots.none { root -> resolved == provider.cacheArtifactPath(root, logicalName) }) {
              return@mapNotNull null
            }
            NativeAgentLinkInventoryEntry(
              logicalName,
              provider.name.lowercase(),
              link.toAbsolutePath().normalize(),
              resolved,
              "0".repeat(DIGEST_HEX_LENGTH),
              home.resolve(".skill-bill/native-agents"),
            )
          }.toList()
        }
      }
    }

  private fun write(path: Path, entries: List<NativeAgentLinkInventoryEntry>) {
    path.parent?.let(Files::createDirectories)
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
    val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
    try {
      Files.write(temporary, bytes)
      try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun com.fasterxml.jackson.databind.JsonNode.requiredText(field: String): String =
    get(field)?.asText()?.takeIf(String::isNotBlank) ?: error("$field is required")

  private fun inventoryPath(home: Path): Path = home.resolve(".skill-bill/native-agent-link-inventory.json")
    .toAbsolutePath().normalize()

  private const val MAX_BYTES = 1024 * 1024L
  private const val DIGEST_HEX_LENGTH = 64
  private const val MAX_SOURCE_ROOT_LENGTH = 4096
  private val LOGICAL_NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
  private val PROVIDERS = setOf("claude", "codex", "opencode", "junie", "zcode")
  private fun provider(id: String) = skillbill.nativeagent.rendering.NativeAgentProvider.entries
    .single { it.name.lowercase() == id }
}
