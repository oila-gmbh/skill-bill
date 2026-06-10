package skillbill.install

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
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.validateInstallPlanWireSnapshot
import skillbill.install.policy.InstallPlanPolicy
import skillbill.ports.install.plan.model.InstallPlanningFacts
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun buildInstallPlan(request: InstallPlanRequest): InstallPlan {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request.targetPaths.platformPacksRoot)
  val policyInput = buildInstallPolicyInput(request, platformManifests)
  val draft = InstallPlanPolicy.buildPlanDraft(policyInput)
  val staging = buildStagingIntent(request, draft.skills, platformManifests)
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
  return InstallPlanPolicy.buildPlanDraft(buildInstallPolicyInput(request, platformManifests)).skills
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
    )
  }
}

internal fun buildInstallStagingIntent(
  request: InstallPlanRequest,
  draftSkills: List<InstallPlanSkill>,
  platformManifests: List<PlatformManifest>,
): InstallStagingIntent {
  return buildStagingIntent(request, draftSkills, platformManifests)
}

private fun buildStagingIntent(
  request: InstallPlanRequest,
  skills: List<InstallPlanSkill>,
  platformManifests: List<PlatformManifest>,
): InstallStagingIntent {
  val stagingRoot = installedSkillsCacheRoot(request.home)
  return InstallStagingIntent(
    root = stagingRoot,
    skillPaths = skills.map { skill ->
      val applicablePointers = applicablePointers(request.repoRoot, skill.sourceDir, platformManifests)
      val supportPointers = generatedSupportPointersFor(
        repoRoot = request.repoRoot,
        sourceSkillDir = skill.sourceDir,
        skillName = skill.name,
        skillsRoot = request.targetPaths.skillsRoot,
      )
      validatePointerInputs(request.repoRoot, skill.sourceDir, applicablePointers, supportPointers)
      val authored = authoredFilesFor(skill.sourceDir, applicablePointers, supportPointers)
      val contentHash = computeInstallContentHash(skill.sourceDir, authored, applicablePointers, supportPointers)
      InstallStagingPathIntent(
        skillName = skill.name,
        sourceDir = skill.sourceDir,
        stagingRoot = stagingRoot,
        stagingDir = installedSkillStagingDir(request.home, skill.sourceDir, contentHash),
        contentHash = contentHash,
      )
    },
  )
}

private fun validatePointerInputs(
  repoRoot: Path,
  sourceSkillDir: Path,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  supportPointers: List<GeneratedSupportPointer>,
) {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val realRepoRoot = repoRoot.toRealPath()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  applicablePointers.forEach { (manifest, spec) ->
    val resolvedPackRoot = manifest.packRoot.toAbsolutePath().normalize()
    val pointerDir = resolvedPackRoot.resolve(spec.skillRelativeDir).normalize()
    val pointerFile = pointerDir.resolve(spec.name).normalize()
    val targetFile = resolvedRepoRoot.resolve(spec.target).normalize()
    require(targetFile.startsWith(resolvedRepoRoot)) {
      "Pointer '${spec.name}' target '${spec.target}' escapes repoRoot '$resolvedRepoRoot'."
    }
    require(pointerFile.startsWith(resolvedRepoRoot)) {
      "Pointer '${spec.name}' under '${spec.skillRelativeDir}' escapes repoRoot '$resolvedRepoRoot'."
    }
    require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
        "which does not exist at '$targetFile'."
    }
    val realTargetFile = targetFile.toRealPath()
    require(realTargetFile.startsWith(realRepoRoot)) {
      "Pointer '${spec.name}' target '${spec.target}' escapes repoRoot '$resolvedRepoRoot' " +
        "through real path '$realTargetFile'."
    }
    require(pointerFile != targetFile) {
      "Pointer '${spec.name}' under '${spec.skillRelativeDir}' resolves to itself at '$pointerFile'."
    }
  }
  supportPointers.forEach { pointer ->
    val targetFile = pointer.target.toAbsolutePath().normalize()
    val pointerFile = resolvedSource.resolve(pointer.name).normalize()
    require(pointerFile.startsWith(resolvedSource)) {
      "Supporting pointer '${pointer.name}' staging path '$pointerFile' escapes source skill dir '$resolvedSource'."
    }
    require(targetFile.startsWith(resolvedRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot'."
    }
    require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      "Supporting pointer '${pointer.name}' targets '$targetFile' which does not exist."
    }
    val realTargetFile = targetFile.toRealPath()
    require(realTargetFile.startsWith(realRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot' " +
        "through real path '$realTargetFile'."
    }
    require(pointerFile != targetFile) {
      "Supporting pointer '${pointer.name}' resolves to itself at '$targetFile'."
    }
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
      claudeCommandTargets(home).map { commandPath -> InstallAgentDefaultTarget(agent = agent, path = commandPath) }
    } else {
      listOf(InstallAgentDefaultTarget(agent = agent, path = path))
    }
  }
