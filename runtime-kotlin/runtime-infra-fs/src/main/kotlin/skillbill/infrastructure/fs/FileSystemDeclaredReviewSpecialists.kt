package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.MissingManifestError
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.scaffold.platformpack.loadPlatformManifest
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemDeclaredReviewSpecialists : DeclaredReviewSpecialistsPort {
  override fun declaredSpecialists(repoRoot: Path): List<String> {
    val platformPacksRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(platformPacksRoot)) return emptyList()
    val packDirs = Files.list(platformPacksRoot).use { stream ->
      stream
        .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
        .sorted()
        .toList()
    }
    return packDirs.flatMap { packDir ->
      val manifest = try {
        loadPlatformManifest(packDir)
      } catch (_: MissingManifestError) {
        null
      }
      manifest?.let { m ->
        m.declaredCodeReviewAreas.map { area -> "bill-${m.slug}-code-review-$area" }
      } ?: emptyList()
    }
  }
}
