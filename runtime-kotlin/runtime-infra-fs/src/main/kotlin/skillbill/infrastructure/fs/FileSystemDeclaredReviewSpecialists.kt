package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.MissingManifestError
import skillbill.install.nativeagent.NativeAgentLinkInventory
import skillbill.model.EnvironmentContext
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.InstalledReviewCatalogPort
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.validatePlatformPackFallbacks
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemDeclaredReviewSpecialists(
  private val installedCatalog: InstalledReviewCatalogPort = InstalledReviewCatalogPort.NONE,
) : DeclaredReviewSpecialistsPort {
  override fun routedSpecialists(repoRoot: Path, changedPaths: List<String>): List<String> {
    if (changedPaths.isEmpty()) return emptyList()
    val manifests = installedCatalog.manifests().ifEmpty { discoverManifests(repoRoot) }
    if (manifests.isEmpty()) return emptyList()
    val changedFiles = changedPaths.map { relativePath ->
      val path = repoRoot.resolve(relativePath).normalize()
      val content = if (path.startsWith(repoRoot.normalize()) && Files.isRegularFile(path)) {
        Files.readString(path)
      } else {
        ""
      }
      ReviewRoutingChangedFile(relativePath, content)
    }
    val routing = ReviewStackRouting.route(manifests, changedFiles)
    val selectedAreas = manifests.flatMap { it.declaredCodeReviewAreas }.toSet()
    return routing.routedSlugs.flatMap { slug ->
      val owned = changedFiles.filter { it.path in routing.ownedPathsBySlug[slug].orEmpty() }
      ReviewLaunchPlanPolicy.flatten(slug, manifests, selectedAreas).lanes
        .filter { lane ->
          owned.any { ReviewLaneInclusionPolicy.ownsChangedFile(lane, it.path, it.changedContent) }
        }
        .map { it.skillName }
    }.distinct()
  }

  private fun discoverManifests(repoRoot: Path): List<PlatformManifest> {
    val platformPacksRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(platformPacksRoot)) return emptyList()
    val packDirs = Files.list(platformPacksRoot).use { stream ->
      stream
        .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
        .sorted()
        .toList()
    }
    return packDirs.mapNotNull { packDir ->
      try {
        loadPlatformManifest(packDir)
      } catch (_: MissingManifestError) {
        null
      }
    }.also(::validatePlatformPackFallbacks)
  }
}

@Inject
class FileSystemInstalledReviewCatalog(
  private val environment: EnvironmentContext,
) : InstalledReviewCatalogPort {
  override fun manifests(): List<PlatformManifest> {
    val catalogRoots = NativeAgentLinkInventory.read(environment.userHome, emptyList())
      .map { it.cacheTargetPath.parent.parent.resolve("review-catalog/platform-packs") }
      .distinct()
      .filter(Files::isDirectory)
    val root = catalogRoots.maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return emptyList()
    return Files.list(root).use { stream ->
      stream.filter(Files::isDirectory).sorted().map(::loadPlatformManifest).toList()
    }.also(::validatePlatformPackFallbacks)
  }
}
