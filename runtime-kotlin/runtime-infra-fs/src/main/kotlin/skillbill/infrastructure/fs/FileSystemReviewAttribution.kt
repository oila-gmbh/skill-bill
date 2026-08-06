package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.model.EnvironmentContext
import skillbill.ports.review.InstalledReviewCatalogPort
import skillbill.ports.review.ReviewAttributionPort
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewAttribution(
  private val context: EnvironmentContext,
  private val installedCatalog: InstalledReviewCatalogPort = InstalledReviewCatalogPort.NONE,
) : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> =
    platformReviewAttributionMappings(repoRoot().resolve("platform-packs"))

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan {
    val manifests = installedCatalog.manifests().ifEmpty { discoverManifests() }
    if (manifests.none { it.slug == routedPackSlug }) return ReviewLaunchPlan(routedPackSlug, emptyList())
    // The routed pack's own composition, never the union across every installed manifest: that union
    // put areas this pack never declares into its plan, and the completeness ledger reads a plan lane
    // as a lane the run launched.
    val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(routedPackSlug, manifests)
    return ReviewLaunchPlanPolicy.flatten(routedPackSlug, manifests, selectedAreas)
  }

  private fun discoverManifests(): List<PlatformManifest> {
    val platformPacksRoot = repoRoot().resolve("platform-packs")
    if (!Files.isDirectory(platformPacksRoot)) return emptyList()
    return discoverPlatformPackManifests(platformPacksRoot)
  }

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
