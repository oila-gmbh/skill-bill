package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import java.nio.file.Path

@Inject
class ExternalAddonOverlayService(
  private val configPort: ExternalAddonSourceConfigPort,
  private val overlayPort: ExternalAddonOverlayPort,
) {
  fun resolveSources(home: Path, environment: Map<String, String> = emptyMap()): List<ExternalAddonSource> =
    configPort.readExternalAddonSources(ExternalAddonSourceConfigRequest(home, environment)).sources

  fun applyOverlay(
    platformPacksRoot: Path,
    home: Path,
    environment: Map<String, String> = emptyMap(),
  ): ExternalAddonOverlayResult {
    val sources = resolveSources(home, environment)
    if (sources.isEmpty()) {
      return ExternalAddonOverlayResult(touched = false)
    }
    return overlayPort.applyOverlay(ExternalAddonOverlayRequest(platformPacksRoot, sources))
  }
}
