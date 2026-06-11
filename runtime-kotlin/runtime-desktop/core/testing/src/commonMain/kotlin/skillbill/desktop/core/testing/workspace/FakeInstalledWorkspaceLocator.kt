package skillbill.desktop.core.testing.workspace

import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator

class FakeInstalledWorkspaceLocator(
  var result: InstalledWorkspaceAvailability = InstalledWorkspaceAvailability(path = "", availability = false),
) : InstalledWorkspaceLocator {
  var locateCallCount: Int = 0
    private set

  override fun locate(): InstalledWorkspaceAvailability {
    locateCallCount++
    return result
  }
}
