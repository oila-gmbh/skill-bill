package skillbill.desktop.core.testing.install

import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway

class FakeDesktopFirstRunGateway(
  var discoveryResult: FirstRunDiscoveryResult,
  var planResult: FirstRunPlanResult,
  var applyResult: FirstRunApplyResult,
  var existingInstall: Boolean = false,
) : DesktopFirstRunGateway {
  val planRequests: MutableList<FirstRunSetupRequest> = mutableListOf()
  val appliedPlans: MutableList<FirstRunInstallPlan> = mutableListOf()

  var discoveryCallCount: Int = 0
    private set

  var planCallCount: Int = 0
    private set

  var applyCallCount: Int = 0
    private set

  override fun hasExistingInstall(): Boolean = existingInstall

  override suspend fun discoverSetup(): FirstRunDiscoveryResult {
    discoveryCallCount += 1
    return discoveryResult
  }

  override suspend fun planSetup(request: FirstRunSetupRequest): FirstRunPlanResult {
    planCallCount += 1
    planRequests += request
    return planResult
  }

  override suspend fun applySetup(plan: FirstRunInstallPlan): FirstRunApplyResult {
    applyCallCount += 1
    appliedPlans += plan
    return applyResult
  }
}
