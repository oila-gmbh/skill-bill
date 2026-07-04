package skillbill.ports.install.addon

import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest

interface ExternalAddonSourceConfigPort {
  fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult

  fun registerExternalAddonSource(request: ExternalAddonSourceRegistrationRequest): ExternalAddonSourceConfigResult
}
