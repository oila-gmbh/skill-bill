package skillbill.install.plan

import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.validateInstallPlanWireSnapshot
import skillbill.install.policy.InstallPlanPolicy
import skillbill.install.support.claudeSkillTargets
import skillbill.ports.install.plan.model.InstallPlanningFacts
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun buildInstallPlan(request: InstallPlanRequest): InstallPlan {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request.targetPaths.platformPacksRoot)
  val policyInput = buildInstallPolicyInput(request, platformManifests)
  val draft = InstallPlanPolicy.buildPlanDraft(policyInput)
  validateInstallPlanInternalSkills(draft.skills)
  val staging = buildInstallStagingIntent(request, draft.skills, platformManifests)
  val plan = draft.toInstallPlan(staging)
  // SKILL-48 Subtask 2b: validate the install-plan wire shape at the
  // builder seam. The CLI emission boundary validates again — both
  // sites cover the parse seam so any regression in shape (e.g. an
  // unknown agent id slipping through a future builder change)
  // loud-fails with `InvalidInstallPlanSchemaError` before the plan is
  // handed to the apply pipeline.
  validateInstallPlanWireSnapshot(plan, InstallPlanWireValidatorAdapter())
  return plan
}

private fun requireSupportedAgentContract() {
  require(SUPPORTED_AGENTS == InstallAgent.supportedIds) {
    "Install plan supported-agent contract drifted. Domain=${InstallAgent.supportedIds}; core=$SUPPORTED_AGENTS."
  }
}

private fun buildInstallPolicyInput(
  request: InstallPlanRequest,
  platformManifests: List<PlatformManifest>,
): InstallPolicyInput {
  val discoveredPlatformPacks = platformManifests.toDiscoverySnapshots()
  val materializationPlan = InstallPlanPolicy.planPlatformSkillMaterialization(
    InstallPlatformSkillMaterializationRequest(
      installRequest = request,
      platformPacks = discoveredPlatformPacks,
    ),
  )
  val selectedPlatformSlugs = materializationPlan.selectedPlatformSlugs.toSet()
  return InstallPolicyInput(
    request = request,
    baseSkills = discoverBaseSkills(request.targetPaths.skillsRoot),
    platformPacks = platformManifests.map { manifest ->
      InstallPlatformPackSnapshot(
        slug = manifest.slug,
        packRoot = manifest.packRoot,
        skills = if (manifest.slug in selectedPlatformSlugs) {
          platformSkills(manifest)
        } else {
          emptyList()
        },
        baselineLayers = manifest.codeReviewComposition?.baselineLayers.orEmpty(),
      )
    },
    detectedAgentTargets = detectAgents(request.home).map { target ->
      InstallAgentTarget(
        agent = InstallAgent.fromId(target.name),
        path = target.path,
        source = InstallAgentTargetSource.DETECTED,
      )
    },
    defaultAgentTargets = claudeMultiRootDefaultTargets(request.home),
  )
}

/**
 * SKILL-76 Subtask 2: enumerate every install-plan skill (base + all materialized
 * platform-pack skills) for [request]. Lives in the approved builder seam so the
 * reconcile policy can reuse skill enumeration WITHOUT referencing the domain
 * `InstallPlanPolicy` directly (InstallPolicyOwnershipArchitectureTest restricts
 * policy callers to this file). Returns the same `draft.skills` the staging-intent
 * builder consumes.
 */
internal fun enumerateInstallPlanSkills(request: InstallPlanRequest): List<InstallPlanSkill> {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request.targetPaths.platformPacksRoot)
  val skills = InstallPlanPolicy.buildPlanDraft(buildInstallPolicyInput(request, platformManifests)).skills
  validateInstallPlanInternalSkills(skills)
  return skills
}

internal fun collectInstallPlanningFacts(request: InstallPlanRequest): InstallPlanningFacts {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request.targetPaths.platformPacksRoot)
  return InstallPlanningFacts(
    baseSkills = discoverBaseSkills(request.targetPaths.skillsRoot),
    platformManifests = platformManifests,
    detectedAgentTargets = detectAgents(request.home).map { target ->
      InstallAgentTarget(
        agent = InstallAgent.fromId(target.name),
        path = target.path,
        source = InstallAgentTargetSource.DETECTED,
      )
    },
    defaultAgentTargets = claudeMultiRootDefaultTargets(request.home),
  )
}

internal fun materializeSelectedPlatformSkills(
  platformManifests: List<PlatformManifest>,
  selectedPlatformSlugs: List<String>,
): List<InstallPlatformPackSnapshot> {
  val selected = selectedPlatformSlugs.toSet()
  return platformManifests.map { manifest ->
    InstallPlatformPackSnapshot(
      slug = manifest.slug,
      packRoot = manifest.packRoot,
      skills = if (manifest.slug in selected) {
        platformSkills(manifest)
      } else {
        emptyList()
      },
      baselineLayers = manifest.codeReviewComposition?.baselineLayers.orEmpty(),
    )
  }
}

/**
 * Default agent targets, expanding claude into one row per discovered config root so install/apply
 * fans skill links across every profile while other agents keep their single default path.
 */
private fun claudeMultiRootDefaultTargets(home: Path): List<InstallAgentDefaultTarget> =
  agentPaths(home).flatMap { (agentId, path) ->
    val agent = InstallAgent.fromId(agentId)
    if (agentId == "claude") {
      claudeSkillTargets(home).map { skillPath -> InstallAgentDefaultTarget(agent = agent, path = skillPath) }
    } else {
      listOf(InstallAgentDefaultTarget(agent = agent, path = path))
    }
  }
