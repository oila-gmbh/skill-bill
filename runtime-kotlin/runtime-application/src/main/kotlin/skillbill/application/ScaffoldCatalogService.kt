package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import java.nio.file.Path

@Inject
class ScaffoldCatalogService(
  private val gateway: ScaffoldCatalogGateway,
) {
  fun approvedCodeReviewAreas(): Set<String> = gateway.approvedCodeReviewAreas()

  fun preShellFamilies(): Set<String> = gateway.preShellFamilies()

  fun shelledFamilies(): Set<String> = gateway.shelledFamilies()

  fun platformPackPresets(): Map<String, String> = gateway.platformPackPresets()

  fun scaffoldPayloadVersion(): String = gateway.scaffoldPayloadVersion()

  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection> =
    gateway.discoverPilotedPlatformPacks(packsRoot)

  fun discoverBaselineReviewCatalog(packsRoot: Path) = gateway.discoverBaselineReviewCatalog(packsRoot)
}
