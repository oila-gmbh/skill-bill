package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

@Inject
class ValidationGateResolver(
  private val scaffoldCatalogService: ScaffoldCatalogService,
) {
  fun resolve(repoRoot: Path, changedPaths: List<String>): ValidationGateResolution {
    val packsRoot = repoRoot.resolve("platform-packs")
    val manifests = scaffoldCatalogService.discoverPlatformManifests(packsRoot)
    if (manifests.isEmpty()) {
      return ValidationGateResolution.Absent(null)
    }
    val routing = ReviewStackRouting.route(
      manifests,
      changedPaths.map { ReviewRoutingChangedFile(it, "") },
    )
    val dominant = selectDominantPack(manifests, routing.routedSlugs)
    val declaration = dominant?.validationGate
    return if (dominant != null && declaration != null) {
      ValidationGateResolution.Declared(dominant.slug, declaration)
    } else {
      ValidationGateResolution.Absent(dominant?.slug)
    }
  }

  private fun selectDominantPack(manifests: List<PlatformManifest>, routedSlugs: Set<String>): PlatformManifest? {
    if (routedSlugs.isEmpty()) return null
    return manifests.firstOrNull { it.slug in routedSlugs }
  }
}
