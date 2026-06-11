package skillbill.desktop.core.testing.workspace

import skillbill.desktop.core.domain.model.ProvisionResult
import skillbill.desktop.core.domain.service.InstalledWorkspaceGitProvisioner

class FakeInstalledWorkspaceGitProvisioner(
  var result: ProvisionResult = ProvisionResult.AlreadyProvisioned,
) : InstalledWorkspaceGitProvisioner {
  var provisionCallCount: Int = 0
    private set

  val provisionedRoots: MutableList<String> = mutableListOf()

  override fun provision(workspaceRoot: String): ProvisionResult {
    provisionCallCount++
    provisionedRoots.add(workspaceRoot)
    return result
  }
}
