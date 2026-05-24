package skillbill.install.model

import java.nio.file.Path

data class InstallAgentDefaultTarget(
  val agent: InstallAgent,
  val path: Path,
)

data class InstallPlatformPackSnapshot(
  val slug: String,
  val packRoot: Path,
  val skills: List<InstallPlanSkill>,
)

data class InstallPlatformPackDiscoverySnapshot(
  val slug: String,
  val packRoot: Path,
)

data class InstallPlatformSkillMaterializationRequest(
  val installRequest: InstallPlanRequest,
  val platformPacks: List<InstallPlatformPackDiscoverySnapshot>,
)

data class InstallPlatformSkillMaterializationPlan(
  val selectedPlatformSlugs: List<String>,
)

data class InstallPolicyInput(
  val request: InstallPlanRequest,
  val baseSkills: List<InstallPlanSkill>,
  val platformPacks: List<InstallPlatformPackSnapshot>,
  val detectedAgentTargets: List<InstallAgentTarget>,
  val defaultAgentTargets: List<InstallAgentDefaultTarget>,
)

data class InstallPlanDraft(
  val request: InstallPlanRequest,
  val agents: List<InstallAgentTarget>,
  val discoveredPlatformPacks: List<PlannedPlatformPack>,
  val selectedPlatformSlugs: List<String>,
  val skills: List<InstallPlanSkill>,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationIntent: McpRegistrationIntent,
  val runtimeDistributionInputs: RuntimeDistributionInputs,
  val installationTargetPaths: InstallationTargetPaths,
  val windowsSymlinkPreflight: WindowsSymlinkPreflight,
) {
  fun toInstallPlan(staging: InstallStagingIntent): InstallPlan = InstallPlan(
    request = request,
    agents = agents,
    discoveredPlatformPacks = discoveredPlatformPacks,
    selectedPlatformSlugs = selectedPlatformSlugs,
    skills = skills,
    staging = staging,
    telemetryLevel = telemetryLevel,
    mcpRegistrationIntent = mcpRegistrationIntent,
    runtimeDistributionInputs = runtimeDistributionInputs,
    installationTargetPaths = installationTargetPaths,
    windowsSymlinkPreflight = windowsSymlinkPreflight,
  )
}

data class InstallPolicyValidationResult(
  val status: InstallPolicyValidationStatus,
)

enum class InstallPolicyValidationStatus {
  VALID,
}
