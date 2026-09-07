package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.MissingInstalledNativeAgentError
import skillbill.install.nativeagent.NativeAgentLinkInventory
import skillbill.install.nativeagent.NativeAgentLinkInventoryEntry
import skillbill.model.EnvironmentContext
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Inject
class FileSystemReviewLaunchAgentStaging(
  private val environment: EnvironmentContext,
) : ReviewLaunchAgentStagingPort {
  override fun stage(request: ReviewLaunchAgentStagingRequest) {
    if (request.logicalWorkerNames.isEmpty()) return
    val provider = provider(request.agentId)
      ?: throw MissingInstalledNativeAgentError(
        request.logicalWorkerNames.first(),
        request.agentId,
        environment.userHome.toString(),
        "provider does not support native-agent staging",
        REPAIR_COMMAND,
      )
    val inventory = NativeAgentLinkInventory.read(environment.userHome, emptyList())
    request.logicalWorkerNames.distinct().forEach { logicalName ->
      val entry = inventoryEntry(inventory, provider, logicalName)
      copyArtifact(entry, provider, request.reviewLaunchDirectory)
    }
  }

  private fun inventoryEntry(
    inventory: List<NativeAgentLinkInventoryEntry>,
    provider: NativeAgentProvider,
    logicalName: String,
  ): NativeAgentLinkInventoryEntry {
    val entries = inventory.filter {
      it.provider == provider.name.lowercase() && it.logicalName == logicalName
    }
    if (entries.isEmpty()) {
      fail(
        logicalName,
        provider,
        provider.homeAgentDirs(environment.userHome).first().resolve(provider.fileName(logicalName)),
        "managed inventory entry is missing",
      )
    }
    val cachePath = entries.first().cacheTargetPath
    if (!Files.isReadable(cachePath)) {
      fail(logicalName, provider, cachePath, "managed cache artifact is missing or unreadable")
    }
    return entries.first()
  }

  private fun copyArtifact(
    entry: NativeAgentLinkInventoryEntry,
    provider: NativeAgentProvider,
    reviewLaunchDirectory: Path,
  ) {
    val destinationDir = reviewLaunchDirectory.resolve(".cursor/agents")
    Files.createDirectories(destinationDir)
    val destination = destinationDir.resolve(provider.fileName(entry.logicalName))
    try {
      Files.copy(entry.cacheTargetPath, destination, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: IOException) {
      fail(entry.logicalName, provider, destination, "failed to copy managed cache artifact", error)
    }
  }

  private fun provider(agentId: String): NativeAgentProvider? = when (agentId) {
    "cursor" -> NativeAgentProvider.Cursor
    else -> null
  }

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
