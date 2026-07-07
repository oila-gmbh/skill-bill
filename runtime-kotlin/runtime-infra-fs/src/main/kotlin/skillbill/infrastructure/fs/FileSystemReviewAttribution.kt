package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.model.EnvironmentContext
import skillbill.ports.review.ReviewAttributionPort
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewAttribution(
  private val context: EnvironmentContext,
) : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> =
    platformReviewAttributionMappings(repoRoot().resolve("platform-packs"))

  private fun repoRoot(): Path = Path.of(context.environment["SKILL_BILL_REPO_ROOT"] ?: System.getProperty("user.dir"))
    .toAbsolutePath()
    .normalize()
}

fun platformReviewAttributionMappings(platformPacksRoot: Path): Map<String, String> {
  if (!Files.isDirectory(platformPacksRoot)) {
    return emptyMap()
  }
  return platformReviewAttributionMappings(discoverPlatformPackManifests(platformPacksRoot))
}

fun platformReviewAttributionMappings(manifests: List<PlatformManifest>): Map<String, String> = manifests
  .sortedBy(PlatformManifest::slug)
  .flatMap { manifest ->
    manifest.declaredCodeReviewSkillNames().sorted().map { skillName -> skillName to manifest.slug }
  }
  .toMap()
