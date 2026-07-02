package skillbill.ports.install.addon

import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult

interface ExternalAddonOverlayPort {
  fun applyOverlay(request: ExternalAddonOverlayRequest): ExternalAddonOverlayResult
}
