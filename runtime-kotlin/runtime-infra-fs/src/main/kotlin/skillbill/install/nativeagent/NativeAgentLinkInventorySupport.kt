package skillbill.install.nativeagent

import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal object NativeAgentLinkInventoryLimits {
  const val MAX_BYTES = 1024 * 1024L
  const val DIGEST_HEX_LENGTH = 64
  val EMPTY_DIGEST = "0".repeat(DIGEST_HEX_LENGTH)
  const val MAX_SOURCE_ROOT_LENGTH = 4096
  val LOGICAL_NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
  val CACHE_GENERATION = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,31})-)?[0-9a-f]{16}")
  val PROVIDERS = setOf("claude", "codex", "junie", "cursor")
}

internal object NativeAgentLinkInventorySupport {
  fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

  fun canonicalManagedCacheRoots(home: Path, managedRoots: List<Path>): List<Path> {
    val normalizedHome = home.toAbsolutePath().normalize()
    val installedGenerations = generationChildren(
      normalizedHome.resolve(".skill-bill/installed-skills"),
      prefix = "native-agents-",
    )
    val legacyGenerations = generationChildren(normalizedHome.resolve(".skill-bill/native-agents"))
    return (managedRoots.map { it.toAbsolutePath().normalize() } + installedGenerations + legacyGenerations).distinct()
  }

  fun inventoryPath(home: Path): Path = home.resolve(".skill-bill/native-agent-link-inventory.json")
    .toAbsolutePath().normalize()

  fun provider(id: String): NativeAgentProvider = NativeAgentProvider.entries
    .single { it.name.lowercase() == id }

  private fun generationChildren(parent: Path, prefix: String = ""): List<Path> {
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.list(parent).use { children ->
      children.filter { child ->
        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
          child.fileName.toString().removePrefix(prefix).let { leaf ->
            child.fileName.toString().startsWith(prefix) &&
              NativeAgentLinkInventoryLimits.CACHE_GENERATION.matches(leaf)
          }
      }.map { it.toAbsolutePath().normalize() }.toList()
    }
  }
}
