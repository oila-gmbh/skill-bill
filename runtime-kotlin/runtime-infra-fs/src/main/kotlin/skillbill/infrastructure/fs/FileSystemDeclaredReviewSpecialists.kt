package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile

@Inject
class FileSystemDeclaredReviewSpecialists(
  private val installedCatalog: InstalledPlatformPackCatalogPort = InstalledPlatformPackCatalogPort.NONE,
) : DeclaredReviewSpecialistsPort {
  override fun routedSpecialists(changedFiles: List<ReviewRoutingChangedFile>): List<String> {
    if (changedFiles.isEmpty()) return emptyList()
    val manifests = installedCatalog.manifests()
    if (manifests.isEmpty()) return emptyList()
    val routing = ReviewStackRouting.route(manifests, changedFiles)
    return routing.routedSlugs.flatMap { slug ->
      val owned = changedFiles.filter { it.path in routing.ownedPathsBySlug[slug].orEmpty() }
      val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(slug, manifests)
      ReviewLaunchPlanPolicy.flatten(slug, manifests, selectedAreas).lanes
        .filter { lane ->
          owned.any { ReviewLaneInclusionPolicy.ownsChangedFile(lane, it.path, it.changedContent) }
        }
        .map { it.skillName }
    }.distinct()
  }
}
