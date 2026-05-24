package skillbill.ports.install.plan.model

import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallPlanDraft
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallStagingIntent
import skillbill.scaffold.model.PlatformManifest

data class InstallPlanningFactsRequest(
  val installRequest: InstallPlanRequest,
)

data class InstallPlanningFactsResult(
  val facts: InstallPlanningFacts,
)

data class InstallPlanningFacts(
  val baseSkills: List<InstallPlanSkill>,
  val platformManifests: List<PlatformManifest>,
  val detectedAgentTargets: List<InstallAgentTarget>,
  val defaultAgentTargets: List<InstallAgentDefaultTarget>,
)

data class InstallPlatformSkillMaterializationPortRequest(
  val installRequest: InstallPlanRequest,
  val platformManifests: List<PlatformManifest>,
  val selectedPlatformSlugs: List<String>,
)

data class InstallPlatformSkillMaterializationPortResult(
  val platformPacks: List<InstallPlatformPackSnapshot>,
)

data class InstallStagingIntentRequest(
  val installRequest: InstallPlanRequest,
  val draft: InstallPlanDraft,
  val platformManifests: List<PlatformManifest>,
)

data class InstallStagingIntentResult(
  val staging: InstallStagingIntent,
)
