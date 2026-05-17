package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest

interface DesktopFirstRunGateway {
  suspend fun discoverSetup(): FirstRunDiscoveryResult

  suspend fun planSetup(request: FirstRunSetupRequest): FirstRunPlanResult

  suspend fun applySetup(plan: FirstRunInstallPlan): FirstRunApplyResult
}
