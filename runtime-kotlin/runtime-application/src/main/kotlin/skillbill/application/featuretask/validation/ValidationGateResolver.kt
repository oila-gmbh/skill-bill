package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.error.ShellContentContractException
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.review.plan.model.ReviewStackRoutingResult
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
    val dominant = selectDominantPack(manifests, routing)
    val declaration = dominant?.validationGate
    return if (dominant != null && declaration != null) {
      ValidationGateResolution.Declared(dominant.slug, declaration)
    } else {
      ValidationGateResolution.Absent(dominant?.slug)
    }
  }

  /**
   * Pick the pack whose validation_gate (if any) the build/validate phases should run.
   *
   * Catalog order must not decide this: review routing can co-route a no-gate fallback (e.g. generic
   * for an unmatched `.txt`) alongside the stack pack that owns the `.kt` files. Taking
   * `manifests.first { slug in routed }` then preferred `generic` alphabetically and blocked build
   * even though Kotlin declared the gate. Prefer routed packs that declare a gate, ranked by how
   * many changed paths they own. With no routed slugs, keep the prior empty-path fallback: first
   * pack that declares a gate.
   */
  private fun selectDominantPack(
    manifests: List<PlatformManifest>,
    routing: ReviewStackRoutingResult,
  ): PlatformManifest? {
    val bySlug = manifests.associateBy { it.slug }
    val routed = routing.routedSlugs.mapNotNull(bySlug::get)
    if (routed.isEmpty()) {
      return manifests.firstOrNull { it.validationGate != null } ?: manifests.firstOrNull()
    }
    val gated = routed.filter { it.validationGate != null }
    if (gated.isEmpty()) {
      return routed.firstOrNull()
    }
    return gated.maxByOrNull { pack -> routing.ownedPathsBySlug[pack.slug]?.size ?: 0 }
  }
}
