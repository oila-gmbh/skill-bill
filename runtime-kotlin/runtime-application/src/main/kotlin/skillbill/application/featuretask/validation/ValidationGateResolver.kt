package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ValidationGateDeclaration
import java.nio.file.Path
import java.util.logging.Logger

sealed interface ValidationGateResolution {
  data class Declared(
    val packSlug: String,
    val declaration: ValidationGateDeclaration,
  ) : ValidationGateResolution

  /** Missing pack gate declaration — agent-run validate fallback with surfaced degradation. */
  data class Absent(val routedPackSlug: String?) : ValidationGateResolution
}

@Inject
class ValidationGateResolver(
  private val scaffoldCatalogService: ScaffoldCatalogService,
) {
  fun resolve(repoRoot: Path, changedPaths: List<String>): ValidationGateResolution {
    val packsRoot = repoRoot.resolve("platform-packs")
    val manifests = scaffoldCatalogService.discoverPlatformManifests(packsRoot)
    if (manifests.isEmpty()) {
      emitAbsentDegradation(seam = "ValidationGateResolver.resolve", routedPackSlug = null, cause = "no platform packs")
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
      emitAbsentDegradation(
        seam = "ValidationGateResolver.resolve",
        routedPackSlug = dominant?.slug,
        cause = if (dominant == null) "no routed pack" else "validation_gate absent on routed pack '${dominant.slug}'",
      )
      ValidationGateResolution.Absent(dominant?.slug)
    }
  }

  private fun selectDominantPack(manifests: List<PlatformManifest>, routedSlugs: Set<String>): PlatformManifest? {
    if (routedSlugs.isEmpty()) return null
    return manifests.firstOrNull { it.slug in routedSlugs }
  }

  companion object {
    private val log: Logger = Logger.getLogger("skillbill.application.featuretask.validation.ValidationGateResolver")

    internal fun emitAbsentDegradation(seam: String, routedPackSlug: String?, cause: String) {
      log.warning(
        "validation gate degraded: seam=$seam used=agent-run-validate expected=runtime-owned-gate " +
          "routed_pack=${routedPackSlug ?: "<none>"} cause=$cause",
      )
    }
  }
}
