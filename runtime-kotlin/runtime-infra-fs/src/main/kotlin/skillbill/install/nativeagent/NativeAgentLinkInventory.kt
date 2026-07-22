@file:Suppress("TooGenericExceptionCaught")

package skillbill.install.nativeagent

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
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

  fun reconcile(home: Path, provider: String, desired: List<NativeAgentLinkInventoryEntry>, managedRoots: List<Path>) {
    val path = inventoryPath(home)
    val previous = read(path)
    val desiredPaths = desired.map { it.installedPath.normalize() }.toSet()
    previous.filter { it.provider == provider && it.installedPath.normalize() !in desiredPaths }.forEach { stale ->
      removeIfStillManaged(stale, managedRoots)
    }
    val retained = previous.filter { it.provider != provider }
    write(path, (retained + desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })))
  }

  private fun removeIfStillManaged(entry: NativeAgentLinkInventoryEntry, managedRoots: List<Path>) {
    val link = entry.installedPath
    if (!Files.isSymbolicLink(link)) return
    val recordedTarget = entry.cacheTargetPath.toAbsolutePath().normalize()
    val recordedManaged = managedRoots.any { recordedTarget.startsWith(it.toAbsolutePath().normalize()) } ||
      recordedTarget.toString().contains("/.skill-bill/")
    if (!recordedManaged) return
    val rawTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (resolved == recordedTarget || !Files.exists(link)) Files.deleteIfExists(link)
  }

  private fun read(path: Path): List<NativeAgentLinkInventoryEntry> {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return try {
      require(Files.size(path) <= MAX_BYTES) { "inventory exceeds $MAX_BYTES bytes" }
      val root = mapper.readTree(path.toFile())
      require(root["contract_version"]?.asText() == NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION) {
        "unsupported contract_version"
      }
      root["entries"]?.elements()?.asSequence()?.map { node ->
        NativeAgentLinkInventoryEntry(
          logicalName = node.requiredText("logical_name"),
          provider = node.requiredText("provider"),
          installedPath = Path.of(node.requiredText("installed_path")),
          cacheTargetPath = Path.of(node.requiredText("cache_target_path")),
          contentDigest = node.requiredText("content_digest"),
          sourceRoot = Path.of(node.requiredText("source_root")),
        )
      }?.toList() ?: error("entries is required")
    } catch (error: Exception) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
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
}
