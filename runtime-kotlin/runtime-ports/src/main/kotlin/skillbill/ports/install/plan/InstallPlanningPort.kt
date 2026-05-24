package skillbill.ports.install.plan

import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsResult
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortResult
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.plan.model.InstallStagingIntentResult

interface InstallPlanningFactsPort {
  fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult
}

interface InstallPlatformSkillMaterializationPort {
  fun materializePlatformSkills(
    request: InstallPlatformSkillMaterializationPortRequest,
  ): InstallPlatformSkillMaterializationPortResult
}

interface InstallStagingIntentPort {
  fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult
}
