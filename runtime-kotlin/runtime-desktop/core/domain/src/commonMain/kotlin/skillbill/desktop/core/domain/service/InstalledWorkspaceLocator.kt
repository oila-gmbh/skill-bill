package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability

interface InstalledWorkspaceLocator {
  fun locate(): InstalledWorkspaceAvailability
}
