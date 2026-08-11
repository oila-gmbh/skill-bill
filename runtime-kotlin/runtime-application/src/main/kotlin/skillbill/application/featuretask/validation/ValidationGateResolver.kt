package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.error.ShellContentContractException
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest

@Inject
class ValidationGateResolver(
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) {
  fun resolve(changedPaths: List<String>): ValidationGateResolution {
    val manifests = try {
      installedCatalog.manifests()
    } catch (e: ShellContentContractException) {
      // Blocks rather than degrading to the agent-run fallback: unreadable packs are not the same
      // as no declared gate, and reporting validate as satisfied without pack-attested execution
      // would be worse than stopping.
      return ValidationGateResolution.Incompatible(
        "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the installed platform packs before running validation.",
      )
    }
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
