package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.toPolicyInput
import skillbill.application.install.validatedInstallPlan
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SharedInstallSelection
import skillbill.install.policy.InstallPlanPolicy
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path

@Inject
class InstallService(
  private val planningFactsPort: InstallPlanningFactsPort,
  private val platformSkillMaterializationPort: InstallPlatformSkillMaterializationPort,
  private val stagingIntentPort: InstallStagingIntentPort,
  private val applyExecutionPort: InstallApplyExecutionPort,
  private val skillLinkPort: InstallSkillLinkPort,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
) {
  fun planInstall(request: InstallPlanRequest): InstallPlan {
    val facts = planningFactsPort.collectPlanningFacts(InstallPlanningFactsRequest(request)).facts
    val materializationPlan = InstallPlanPolicy.planPlatformSkillMaterialization(
      InstallPlatformSkillMaterializationRequest(
        installRequest = request,
        platformPacks = facts.platformManifests.map { manifest ->
          InstallPlatformPackDiscoverySnapshot(
            slug = manifest.slug,
            packRoot = manifest.packRoot,
          )
        },
      ),
    )
    val platformPacks = platformSkillMaterializationPort.materializePlatformSkills(
      InstallPlatformSkillMaterializationPortRequest(
        installRequest = request,
        platformManifests = facts.platformManifests,
        selectedPlatformSlugs = materializationPlan.selectedPlatformSlugs,
      ),
    ).platformPacks
    val draft = InstallPlanPolicy.buildPlanDraft(facts.toPolicyInput(request, platformPacks))
    val staging = stagingIntentPort.buildStagingIntent(
      InstallStagingIntentRequest(
        installRequest = request,
        draft = draft,
        platformManifests = facts.platformManifests,
      ),
    ).staging
    return validatedInstallPlan(draft, staging)
  }

  fun applyInstall(plan: InstallPlan, telemetryLevelMutator: TelemetryLevelMutator? = null): InstallApplyResult {
    val result = applyExecutionPort.applyInstall(
      InstallApplyExecutionRequest(
        plan = plan,
        telemetryLevelMutator = telemetryLevelMutator,
      ),
    ).result
    persistSuccessfulInstallSelection(plan, result)
    return result
  }

  fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path? = null, home: Path? = null): List<Path> =
    skillLinkPort.linkSkill(
      InstallSkillLinkRequest(
        source = source,
        targetDir = targetDir,
        agent = agent,
        repoRoot = repoRoot,
        home = home,
      ),
    ).linkedPaths

  private fun persistSuccessfulInstallSelection(plan: InstallPlan, result: InstallApplyResult) {
    if (result.status == InstallApplyStatus.FAILURE) {
      return
    }
    installSelectionPersistencePort.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(
        installHome = plan.request.home,
        selection = SharedInstallSelection(
          selectedAgents = result.resolvedInstalledAgents.agents.ifEmpty {
            plan.agents.mapTo(mutableSetOf()) { target -> target.agent }
          },
          platformPackSelection = persistedPlatformPackSelection(plan),
          telemetryLevel = plan.telemetryLevel,
          mcpRegistrationChoice = plan.request.mcpRegistrationChoice,
        ),
      ),
    )
  }

  private fun persistedPlatformPackSelection(plan: InstallPlan): PlatformPackSelection = PlatformPackSelection(
    mode = plan.request.platformPackSelection.mode,
    selectedSlugs = if (plan.request.platformPackSelection.mode == PlatformPackSelectionMode.SELECTED) {
      plan.selectedPlatformSlugs.toSet()
    } else {
      emptySet()
    },
  )
}
