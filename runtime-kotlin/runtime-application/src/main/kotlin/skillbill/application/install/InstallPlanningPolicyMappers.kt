package skillbill.application.install

import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanDraft
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallStagingIntent
import skillbill.install.policy.InstallPlanPolicy
import skillbill.ports.install.plan.model.InstallPlanningFacts

internal fun InstallPlanningFacts.toPolicyInput(
  request: InstallPlanRequest,
  platformPacks: List<InstallPlatformPackSnapshot>,
): InstallPolicyInput = InstallPolicyInput(
  request = request,
  baseSkills = baseSkills,
  platformPacks = platformPacks,
  detectedAgentTargets = detectedAgentTargets,
  defaultAgentTargets = defaultAgentTargets,
)

internal fun validatedInstallPlan(draft: InstallPlanDraft, staging: InstallStagingIntent): InstallPlan {
  val plan = draft.toInstallPlan(staging)
  InstallPlanPolicy.validateInstallPlanSnapshot(plan)
  return plan
}
