@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package skillbill.infrastructure.fs

import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import skillbill.error.MissingInstalledNativeAgentError
import skillbill.model.EnvironmentContext
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Inject
class FileSystemReviewNativeAgentPreflight(
  private val environment: EnvironmentContext,
) : ReviewNativeAgentPreflightPort {
  override fun verify(request: ReviewNativeAgentPreflightRequest) {
    val home = environment.userHome
    val inventoryPath = home.resolve(".skill-bill/native-agent-link-inventory.json")
    val inventory = readInventory(inventoryPath)
    request.agentIds.distinct().forEach { agentId ->
      val provider = provider(agentId) ?: throw MissingInstalledNativeAgentError(
        request.logicalNames.firstOrNull() ?: "unknown",
        agentId,
        environment.userHome.toString(),
        "provider does not support native-agent selection",
        REPAIR_COMMAND,
      )
      request.logicalNames.distinct().forEach { logicalName ->
        val entries = inventory.filter { it.provider == provider.name.lowercase() && it.logicalName == logicalName }
        if (entries.isEmpty()) {
          fail(
            logicalName,
            provider,
            provider.homeAgentDirs(home).first().resolve(provider.fileName(logicalName)),
            "managed inventory entry is missing",
          )
        }
        val activePaths = provider.homeAgentDirs(home).map { it.resolve(provider.fileName(logicalName)).normalize() }.toSet()
        val applicable = entries.filter { it.installedPath.normalize() in activePaths }
        if (applicable.isEmpty() || applicable.map { it.installedPath.normalize() }.distinct().size != applicable.size) {
          fail(logicalName, provider, provider.homeAgentDirs(home).first().resolve(provider.fileName(logicalName)),
            "managed inventory must contain one entry per applicable provider path")
        }
        applicable.forEach { verifyEntry(it, provider, home, request.repoRoot) }
      }
    }
  }

  private fun verifyEntry(entry: InventoryEntry, provider: NativeAgentProvider, home: Path, repoRoot: Path) {
    val installed = entry.installedPath
    val allowedDirs = provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }
    if (allowedDirs.none { installed.toAbsolutePath().normalize().parent == it }) {
      fail(entry.logicalName, provider, installed, "installed path is outside the active provider directories")
    }
    val currentCache = NativeAgentOperations.installCacheRoot(
      home,
      repoRoot.resolve("platform-packs"),
      repoRoot.resolve("skills"),
    ).toAbsolutePath().normalize()
    if (!entry.cacheTargetPath.toAbsolutePath().normalize().startsWith(currentCache)) {
      fail(entry.logicalName, provider, installed, "recorded target is outside the current managed cache")
    }
    if (!Files.isSymbolicLink(installed)) fail(entry.logicalName, provider, installed, "managed link is missing")
    val resolved = runCatching { installed.toRealPath() }
      .getOrElse { fail(entry.logicalName, provider, installed, "managed link is dangling or unreadable", it) }
    val target = runCatching { entry.cacheTargetPath.toRealPath() }
      .getOrElse { fail(entry.logicalName, provider, installed, "managed cache artifact is missing", it) }
    if (resolved != target) {
      fail(
        entry.logicalName,
        provider,
        installed,
        "managed link does not resolve to the recorded cache artifact",
      )
    }
    if (!Files.isReadable(
        resolved,
      )
    ) {
      fail(entry.logicalName, provider, installed, "managed cache artifact is unreadable")
    }
    if (parseLogicalName(resolved, provider) != entry.logicalName) {
      fail(entry.logicalName, provider, installed, "artifact logical name does not match the planned worker")
    }
    if (sha256(
        resolved,
      ) != entry.contentDigest
    ) {
      fail(entry.logicalName, provider, installed, "artifact content digest is stale")
    }
  }

  private fun readInventory(path: Path): List<InventoryEntry> {
    if (!Files.isRegularFile(path)) return emptyList()
    return try {
      require(Files.size(path) <= MAX_INVENTORY_BYTES) { "inventory exceeds $MAX_INVENTORY_BYTES bytes" }
      val root = ObjectMapper().readTree(path.toFile())
      require(root["contract_version"]?.asText() == "0.1") { "unsupported contract_version" }
      root["entries"]?.map { node ->
        InventoryEntry(
          node["logical_name"].requiredText("logical_name"),
          node["provider"].requiredText("provider"),
          Path.of(node["installed_path"].requiredText("installed_path")),
          Path.of(node["cache_target_path"].requiredText("cache_target_path")),
          node["content_digest"].requiredText("content_digest"),
        )
      } ?: error("entries is required")
    } catch (error: Exception) {
      throw InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
    }
  }

  private fun com.fasterxml.jackson.databind.JsonNode?.requiredText(field: String): String =
    this?.asText()?.takeIf(String::isNotBlank) ?: error("$field is required")

  private fun provider(agentId: String): NativeAgentProvider? = when (agentId) {
    "claude" -> NativeAgentProvider.Claude
    "codex" -> NativeAgentProvider.Codex
    "opencode" -> NativeAgentProvider.Opencode
    "junie" -> NativeAgentProvider.Junie
    "zcode" -> NativeAgentProvider.Zcode
    else -> null
  }

  private fun NativeAgentProvider.fileName(logicalName: String) = "$logicalName.$extension"

  private fun parseLogicalName(path: Path, provider: NativeAgentProvider): String {
    val text = Files.readString(path)
    val pattern = if (provider == NativeAgentProvider.Codex) {
      Regex("(?m)^name\\s*=\\s*\\\"([^\\\"]+)\\\"")
    } else {
      Regex("(?m)^name:\\s*['\\\"]?([^'\\\"\\r\\n]+)")
    }
    return pattern.find(text)?.groupValues?.get(1)?.trim()
      ?: fail(path.fileName.toString(), provider, path, "artifact embedded logical name is missing or malformed")
  }

  private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

  private fun fail(
    logicalName: String,
    provider: NativeAgentProvider,
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): Nothing = throw MissingInstalledNativeAgentError(
    logicalName,
    provider.name.lowercase(),
    path.toString(),
    reason,
    REPAIR_COMMAND,
    cause,
  )

  private data class InventoryEntry(
    val logicalName: String,
    val provider: String,
    val installedPath: Path,
    val cacheTargetPath: Path,
    val contentDigest: String,
  )

  private companion object {
    const val MAX_INVENTORY_BYTES = 1024 * 1024L
    const val REPAIR_COMMAND = "skill-bill install apply"
  }
}
