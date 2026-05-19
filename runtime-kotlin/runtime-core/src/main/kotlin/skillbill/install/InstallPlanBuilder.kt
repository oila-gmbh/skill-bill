package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSchemaValidator
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlannedPlatformPack
import skillbill.install.model.buildInstallPlanWireMap
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun buildInstallPlan(request: InstallPlanRequest): InstallPlan {
  requireSupportedAgentContract()
  val agents = resolveAgentTargets(request)
  val installationTargetPaths = request.targetPaths.copy(agentTargets = agents)
  val platformManifests = discoverPlatformManifests(request.targetPaths.platformPacksRoot)
  val selectedPlatformSlugs = selectedPlatformSlugs(request, platformManifests)
  val discoveredPlatformPacks = platformManifests.map { manifest ->
    PlannedPlatformPack(
      slug = manifest.slug,
      packRoot = manifest.packRoot,
      selected = manifest.slug in selectedPlatformSlugs,
    )
  }
  val skills = discoverBaseSkills(request.targetPaths.skillsRoot) +
    platformManifests
      .filter { manifest -> manifest.slug in selectedPlatformSlugs }
      .flatMap(::platformSkills)
  requireUniqueSkillNames(skills)
  val staging = buildStagingIntent(request, skills, platformManifests)
  val plan = InstallPlan(
    request = request,
    agents = agents,
    discoveredPlatformPacks = discoveredPlatformPacks,
    selectedPlatformSlugs = selectedPlatformSlugs,
    skills = skills,
    staging = staging,
    telemetryLevel = request.telemetryLevel,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = request.mcpRegistrationChoice.register,
      runtimeMcpBin = request.mcpRegistrationChoice.runtimeMcpBin,
      agents = agents.map { target -> target.agent },
    ),
    runtimeDistributionInputs = request.runtimeDistributionInputs,
    installationTargetPaths = installationTargetPaths,
    windowsSymlinkPreflight = request.windowsSymlinkPreflight,
  )
  // SKILL-48 Subtask 2b: validate the install-plan wire shape at the
  // builder seam. The CLI emission boundary validates again — both
  // sites cover the parse seam so any regression in shape (e.g. an
  // unknown agent id slipping through a future builder change)
  // loud-fails with `InvalidInstallPlanSchemaError` before the plan is
  // handed to the apply pipeline.
  InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  return plan
}

private fun requireSupportedAgentContract() {
  require(SUPPORTED_AGENTS == InstallAgent.supportedIds) {
    "Install plan supported-agent contract drifted. Domain=${InstallAgent.supportedIds}; core=$SUPPORTED_AGENTS."
  }
}

private fun resolveAgentTargets(request: InstallPlanRequest): List<InstallAgentTarget> =
  when (request.agentSelection.mode) {
    InstallAgentSelectionMode.DETECTED -> resolveDetectionDerivedTargets(request)
    InstallAgentSelectionMode.MANUAL -> resolveManualTargets(request)
  }

private fun resolveDetectionDerivedTargets(request: InstallPlanRequest): List<InstallAgentTarget> {
  val detected = request.agentSelection.detectedTargets.ifEmpty {
    detectAgents(request.home).map { target ->
      InstallAgentTarget(
        agent = InstallAgent.fromId(target.name),
        path = target.path,
        source = InstallAgentTargetSource.DETECTED,
      )
    }
  }
  return detected.map { target -> target.copy(source = InstallAgentTargetSource.DETECTED) }
}

private fun resolveManualTargets(request: InstallPlanRequest): List<InstallAgentTarget> {
  val explicitTargets = request.targetPaths.agentTargets.associateBy { target -> target.agent }
  val manualAgents = request.agentSelection.manualAgents.ifEmpty { explicitTargets.keys }
  return manualAgents
    .sortedBy(InstallAgent::id)
    .map { agent ->
      explicitTargets[agent]?.copy(source = InstallAgentTargetSource.MANUAL)
        ?: InstallAgentTarget(
          agent = agent,
          path = agentPaths(request.home).getValue(agent.id),
          source = InstallAgentTargetSource.MANUAL,
        )
    }
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
