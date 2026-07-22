@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.MissingInstalledNativeAgentError
import skillbill.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.install.nativeagent.NativeAgentLinkInventory
import skillbill.install.nativeagent.NativeAgentLinkInventoryEntry
import skillbill.model.EnvironmentContext
import skillbill.nativeagent.rendering.NativeAgentProvider
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
    val currentCache = currentNativeAgentApplyCacheRoot(
      home,
      request.repoRoot.resolve("platform-packs"),
      request.repoRoot.resolve("skills"),
    )
    val legacyCache = skillbill.nativeagent.rendering.NativeAgentOperations.installCacheRoot(
      home,
      request.repoRoot.resolve("platform-packs"),
      request.repoRoot.resolve("skills"),
    )
    val inventory = NativeAgentLinkInventory.read(home, listOf(currentCache, legacyCache), request.repoRoot)
    request.assignments.distinct().forEach { assignment ->
      val agentId = assignment.agentId
      val logicalName = assignment.logicalName
      val provider = provider(agentId) ?: throw MissingInstalledNativeAgentError(
        logicalName,
        agentId,
        environment.userHome.toString(),
        "provider does not support native-agent selection",
        REPAIR_COMMAND,
      )
      val entries = inventory.filter { it.provider == provider.name.lowercase() && it.logicalName == logicalName }
      if (entries.isEmpty()) {
        fail(
          logicalName,
          provider,
          provider.homeAgentDirs(home).first().resolve(provider.fileName(logicalName)),
          "managed inventory entry is missing",
        )
      }
      val activePaths = activeProviderDirs(provider, home)
        .map { it.resolve(provider.fileName(logicalName)).toAbsolutePath().normalize() }.toSet()
      if (activePaths.isEmpty()) {
        fail(
          logicalName,
          provider,
          provider.homeAgentDirs(home).first().resolve(provider.fileName(logicalName)),
          "active provider directory is missing",
        )
      }
      val applicable = entries.filter { it.installedPath.normalize() in activePaths }
      if (applicable.map { it.installedPath.normalize() }.toSet() != activePaths) {
        fail(
          logicalName,
          provider,
          provider.homeAgentDirs(home).first().resolve(provider.fileName(logicalName)),
          "managed inventory must contain one entry per applicable provider path",
        )
      }
      applicable.forEach { verifyEntry(it, provider, currentCache) }
    }
  }

  private fun verifyEntry(entry: NativeAgentLinkInventoryEntry, provider: NativeAgentProvider, currentCache: Path) {
    val installed = entry.installedPath
    val expectedTarget = currentCache.resolve(provider.directoryName).resolve(provider.fileName(entry.logicalName))
      .toAbsolutePath().normalize()
    if (entry.cacheTargetPath != expectedTarget) {
      fail(entry.logicalName, provider, installed, "recorded target is not the current installed generation")
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

  private fun provider(agentId: String): NativeAgentProvider? = when (agentId) {
    "claude" -> NativeAgentProvider.Claude
    "codex" -> NativeAgentProvider.Codex
    "opencode" -> NativeAgentProvider.Opencode
    "junie" -> NativeAgentProvider.Junie
    "zcode" -> NativeAgentProvider.Zcode
    else -> null
  }

  private fun activeProviderDirs(provider: NativeAgentProvider, home: Path): List<Path> =
    provider.activeHomeAgentDirs(home)

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

  private companion object {
    const val REPAIR_COMMAND = "skill-bill install apply"
  }
}
