package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort

/**
 * Bundles the three plan-phase install ports that are consumed together while
 * building an install plan. Grouping them keeps `InstallService` below the
 * constructor parameter threshold and mirrors the `ports.install.plan`
 * package boundary.
 */
@Inject
class InstallPlanningPorts(
  val planningFactsPort: InstallPlanningFactsPort,
  val platformSkillMaterializationPort: InstallPlatformSkillMaterializationPort,
  val stagingIntentPort: InstallStagingIntentPort,
)
