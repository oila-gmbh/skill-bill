package skillbill.ports.install.addon

import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult

interface ExternalAddonSourceConfigPort {
  fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult
}
