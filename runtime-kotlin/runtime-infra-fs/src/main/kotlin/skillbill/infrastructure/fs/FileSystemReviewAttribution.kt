package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewAttribution(
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) : ReviewAttributionPort {
  /**
   * Telemetry attribution only, and an absent installation legitimately yields no mappings — so an
   * unreadable pack degrades here instead of failing the unrelated command that is reporting. Review
   * routing reads the same catalog and still surfaces contract failures loudly.
   */
  override fun routedSkillPlatformSlugs(): Map<String, String> =
    runCatching { platformReviewAttributionMappings(installedCatalog.manifests()) }.getOrDefault(emptyMap())

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan {
    val manifests = installedCatalog.manifests()
    if (manifests.none { it.slug == routedPackSlug }) return ReviewLaunchPlan(routedPackSlug, emptyList())
    // The routed pack's own composition, never the union across every installed manifest: that union
    // put areas this pack never declares into its plan, and the completeness ledger reads a plan lane
    // as a lane the run launched.
    val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(routedPackSlug, manifests)
    return ReviewLaunchPlanPolicy.flatten(routedPackSlug, manifests, selectedAreas)
  }
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
