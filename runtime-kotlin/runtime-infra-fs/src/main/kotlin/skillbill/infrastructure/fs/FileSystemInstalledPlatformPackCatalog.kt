package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.install.nativeagent.NativeAgentLinkInventory
import skillbill.infrastructure.fs.scaffold.platformpack.loadPlatformManifest
import skillbill.infrastructure.fs.scaffold.platformpack.validatePlatformPackFallbacks
import skillbill.model.EnvironmentContext
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

/**
 * Reads the pack selection published at install time. The on-disk directory is still named
 * `review-catalog/platform-packs` because install writes it under that name; the contents are the
 * selected packs and serve every consumer, review and validate alike.
 */
@Inject
class FileSystemInstalledPlatformPackCatalog(
  private val environment: EnvironmentContext,
) : InstalledPlatformPackCatalogPort {
  override fun manifests(): List<PlatformManifest> {
    val catalogRoots = NativeAgentLinkInventory.read(environment.userHome, emptyList())
      .map { it.cacheTargetPath.parent.parent.resolve("review-catalog/platform-packs") }
      .distinct()
      .filter(Files::isDirectory)
    val root = catalogRoots.maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return emptyList()
    return Files.list(root).use { stream ->
      stream
        .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
        .sorted()
        .map(::loadPlatformManifest)
        .toList()
    }.also(::validatePlatformPackFallbacks)
  }
}
