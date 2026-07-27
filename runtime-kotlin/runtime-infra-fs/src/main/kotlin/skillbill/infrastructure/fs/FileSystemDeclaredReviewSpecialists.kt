package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.MissingManifestError
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.validatePlatformPackFallbacks
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemDeclaredReviewSpecialists : DeclaredReviewSpecialistsPort {
  override fun routedSpecialists(repoRoot: Path, changedPaths: List<String>): List<String> {
    if (changedPaths.isEmpty()) return emptyList()
    val manifests = discoverManifests(repoRoot)
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
    val routedSlugs = ReviewStackRouting.route(manifests, changedFiles).routedSlugs
    return manifests
      .filter { it.slug in routedSlugs }
      .flatMap { manifest ->
        manifest.declaredCodeReviewAreas.map { area -> "bill-${manifest.slug}-code-review-$area" }
      }
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
