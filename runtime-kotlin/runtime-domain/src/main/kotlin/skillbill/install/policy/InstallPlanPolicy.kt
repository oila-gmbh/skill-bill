@file:Suppress("TooManyFunctions") // single install-plan policy seam: selection, agent, snapshot, and baseline guards

package skillbill.install.policy

import skillbill.error.MissingBaselinePlatformSelectionError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanDraft
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlanWireValidator
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationPlan
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallPolicyValidationResult
import skillbill.install.model.InstallPolicyValidationStatus
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlannedPlatformPack
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.validateInstallPlanWireSnapshot

object InstallPlanPolicy {
  fun validateRequest(input: InstallPolicyInput): InstallPolicyValidationResult {
    validatePath("repoRoot", input.request.repoRoot)
    validatePath("home", input.request.home)
    validatePath("targetPaths.skillsRoot", input.request.targetPaths.skillsRoot)
    validatePath("targetPaths.platformPacksRoot", input.request.targetPaths.platformPacksRoot)
    validatePath(
      "runtimeDistributionInputs.runtimeInstallRoot",
      input.request.runtimeDistributionInputs.runtimeInstallRoot,
    )
    validateAgentSelection(input)
    validatePlatformSelection(input.request)
    validateSnapshots(input)
    return InstallPolicyValidationResult(InstallPolicyValidationStatus.VALID)
  }

  fun buildPlanDraft(input: InstallPolicyInput): InstallPlanDraft {
    validateRequest(input)
    val agents = resolveAgentTargets(input)
    val selectedPlatformSlugs = selectedPlatformSlugs(input)
    validateBaselineCoPresence(input, selectedPlatformSlugs)
    val discoveredPlatformPacks = input.platformPacks.map { pack ->
      PlannedPlatformPack(
        slug = pack.slug,
        packRoot = pack.packRoot,
        selected = pack.slug in selectedPlatformSlugs,
      )
    }
    val skills = input.baseSkills +
      input.platformPacks
        .filter { pack -> pack.slug in selectedPlatformSlugs }
        .flatMap(InstallPlatformPackSnapshot::skills)
    requireUniqueSkillNames(skills)
    return InstallPlanDraft(
      request = input.request,
      agents = agents,
      discoveredPlatformPacks = discoveredPlatformPacks,
      selectedPlatformSlugs = selectedPlatformSlugs,
      skills = skills,
      telemetryLevel = input.request.telemetryLevel,
      mcpRegistrationIntent = McpRegistrationIntent(
        register = input.request.mcpRegistrationChoice.register,
        runtimeMcpBin = input.request.mcpRegistrationChoice.runtimeMcpBin,
        agents = agents.map(InstallAgentTarget::agent),
      ),
      runtimeDistributionInputs = input.request.runtimeDistributionInputs,
      installationTargetPaths = input.request.targetPaths.copy(agentTargets = agents),
      windowsSymlinkPreflight = input.request.windowsSymlinkPreflight,
    )
  }

  fun validateInstallPlanSnapshot(
    plan: InstallPlan,
    validator: InstallPlanWireValidator,
  ): InstallPolicyValidationResult {
    validateInstallPlanWireSnapshot(plan, validator)
    return InstallPolicyValidationResult(InstallPolicyValidationStatus.VALID)
  }

  fun planPlatformSkillMaterialization(
    request: InstallPlatformSkillMaterializationRequest,
  ): InstallPlatformSkillMaterializationPlan {
    validatePlatformSelection(request.installRequest)
    validatePlatformPackDiscoverySnapshots(request.platformPacks)
    return InstallPlatformSkillMaterializationPlan(
      selectedPlatformSlugs = selectedPlatformSlugs(
        selection = request.installRequest.platformPackSelection,
        discoveredSlugs = request.platformPacks.map(InstallPlatformPackDiscoverySnapshot::slug),
      ),
    )
  }
}

private fun validateAgentSelection(input: InstallPolicyInput) {
  val selection = input.request.agentSelection
  when (selection.mode) {
    InstallAgentSelectionMode.DETECTED -> {
      require(selection.manualAgents.isEmpty()) {
        "Detected agent selection must not include manual agents: " +
          selection.manualAgents.map(InstallAgent::id).sorted().joinToString(", ") + "."
      }
    }
    InstallAgentSelectionMode.MANUAL -> {
      require(selection.detectedTargets.isEmpty()) {
        "Manual agent selection must not include detected targets: " +
          selection.detectedTargets.map { target -> target.agent.id }.sorted().joinToString(", ") + "."
      }
    }
  }
  requireNoDuplicateAgentTargets("request.detectedTargets", selection.detectedTargets)
  requireNoDuplicateAgentTargets("detectedAgentTargets", input.detectedAgentTargets)
  requireNoDuplicateDefaultTargets(input)
  requireNoDuplicateAgentTargets("targetPaths.agentTargets", input.request.targetPaths.agentTargets)
  input.request.targetPaths.agentTargets.forEach { target ->
    validatePath("targetPaths.agentTargets.${target.agent.id}", target.path)
  }
  selection.detectedTargets.forEach { target ->
    validatePath("agentSelection.detectedTargets.${target.agent.id}", target.path)
  }
  input.detectedAgentTargets.forEach { target ->
    validatePath("detectedAgentTargets.${target.agent.id}", target.path)
  }
  input.defaultAgentTargets.forEach { target ->
    validatePath("defaultAgentTargets.${target.agent.id}", target.path)
  }
  if (selection.mode == InstallAgentSelectionMode.MANUAL && selection.manualAgents.isNotEmpty()) {
    val explicitAgents = input.request.targetPaths.agentTargets.map(InstallAgentTarget::agent).toSet()
    val defaultAgents = input.defaultAgentTargets.map { target -> target.agent }.toSet()
    val missingTargets = selection.manualAgents - explicitAgents - defaultAgents
    require(missingTargets.isEmpty()) {
      "Manual agent selection has no explicit or default target path for agent(s): " +
        missingTargets.map(InstallAgent::id).sorted().joinToString(", ") + "."
    }
  }
}

/**
 * SKILL-104 (PD8): a selected pack whose manifest declares a required `code_review_composition
 * .baseline_layers` entry in an unselected pack would leave the baseline sidecar absent at review
 * time. Loud-fail with [MissingBaselinePlatformSelectionError]; the shell never silently
 * auto-includes a baseline pack. `ALL` selection (every discovered slug selected) is trivially safe.
 * Packs without baseline layers are unaffected.
 */
private fun validateBaselineCoPresence(input: InstallPolicyInput, selectedPlatformSlugs: List<String>) {
  val selected = selectedPlatformSlugs.toSet()
  val bySlug = input.platformPacks.associateBy(InstallPlatformPackSnapshot::slug)
  // ALL selection covers every discovered slug, so any required baseline is in-selection.
  if (selected.containsAll(bySlug.keys)) {
    return
  }
  bySlug.values.forEach { pack ->
    if (pack.slug !in selected) {
      return@forEach
    }
    pack.baselineLayers.forEach { layer ->
      if (layer.required && layer.platform !in selected) {
        throw MissingBaselinePlatformSelectionError(
          selectingSlug = pack.slug,
          requiredBaselineSlug = layer.platform,
          declaringManifestPath = pack.packRoot.resolve("platform.yaml").toString(),
        )
      }
    }
  }
}

private fun validatePlatformSelection(request: InstallPlanRequest) {
  val selection = request.platformPackSelection
  val selectedSlugs = selection.selectedSlugs
  require(selectedSlugs.none(String::isBlank)) {
    "Selected platform slugs must be non-blank."
  }
  when (selection.mode) {
    PlatformPackSelectionMode.NONE -> require(selectedSlugs.isEmpty()) {
      "Platform mode NONE must not include selected slugs: ${selectedSlugs.sorted().joinToString(", ")}."
    }
    PlatformPackSelectionMode.ALL -> require(selectedSlugs.isEmpty()) {
      "Platform mode ALL must not include selected slugs: ${selectedSlugs.sorted().joinToString(", ")}."
    }
    PlatformPackSelectionMode.SELECTED -> require(selectedSlugs.isNotEmpty()) {
      "Platform mode SELECTED requires at least one selected slug."
    }
  }
}

private fun validateSnapshots(input: InstallPolicyInput) {
  requireNoDuplicateSkills("base skills", input.baseSkills)
  input.baseSkills.forEach { skill ->
    validateSkillSnapshot("base skill snapshot '${skill.name}'", skill)
  }
  validatePlatformPackDiscoverySnapshots(
    input.platformPacks.map { pack ->
      InstallPlatformPackDiscoverySnapshot(slug = pack.slug, packRoot = pack.packRoot)
    },
  )
  input.platformPacks.forEach { pack ->
    requireNoDuplicateSkills("platform pack '${pack.slug}'", pack.skills)
    pack.skills.forEach { skill ->
      validateSkillSnapshot("platform pack '${pack.slug}' skill '${skill.name}'", skill)
      require(skill.kind == InstallPlanSkillKind.PLATFORM_PACK) {
        "Platform pack '${pack.slug}' contains non-platform skill '${skill.name}'."
      }
      require(skill.platformSlug == pack.slug) {
        "Platform pack '${pack.slug}' contains skill '${skill.name}' owned by '${skill.platformSlug}'."
      }
    }
  }
  input.baseSkills.forEach { skill ->
    require(skill.kind == InstallPlanSkillKind.BASE) {
      "Base skill snapshot '${skill.name}' must use kind BASE."
    }
    require(skill.platformSlug == null) {
      "Base skill snapshot '${skill.name}' must not declare a platform slug."
    }
  }
}

private fun validatePlatformPackDiscoverySnapshots(platformPacks: List<InstallPlatformPackDiscoverySnapshot>) {
  val duplicatePlatformSlugs = platformPacks
    .groupBy(InstallPlatformPackDiscoverySnapshot::slug)
    .filterValues { packs -> packs.size > 1 }
    .keys
  require(duplicatePlatformSlugs.isEmpty()) {
    "Discovered platform pack snapshots contain duplicate slug(s): " +
      duplicatePlatformSlugs.sorted().joinToString(", ") + "."
  }
  platformPacks.forEach { pack ->
    require(pack.slug.isNotBlank()) {
      "Discovered platform pack snapshots must not contain a blank slug."
    }
    validatePath("platformPacks.${pack.slug}.packRoot", pack.packRoot)
  }
}

private fun validateSkillSnapshot(label: String, skill: InstallPlanSkill) {
  require(skill.name.isNotBlank()) {
    "$label must have a non-blank name."
  }
  validatePath("$label.sourceDir", skill.sourceDir)
}

private fun resolveAgentTargets(input: InstallPolicyInput): List<InstallAgentTarget> =
  when (input.request.agentSelection.mode) {
    InstallAgentSelectionMode.DETECTED -> resolveDetectionDerivedTargets(input)
    InstallAgentSelectionMode.MANUAL -> resolveManualTargets(input)
  }

private fun resolveDetectionDerivedTargets(input: InstallPolicyInput): List<InstallAgentTarget> {
  val selectedTargets = input.request.agentSelection.detectedTargets.ifEmpty { input.detectedAgentTargets }
  return selectedTargets.map { target -> target.copy(source = InstallAgentTargetSource.DETECTED) }
}

private fun resolveManualTargets(input: InstallPolicyInput): List<InstallAgentTarget> {
  // Group (not associateBy) so a multi-root agent like claude keeps every default/explicit row;
  // collapsing to one per agent would silently drop all but the last profile root.
  val explicitTargets = input.request.targetPaths.agentTargets.groupBy(InstallAgentTarget::agent)
  val defaultTargets = input.defaultAgentTargets.groupBy { target -> target.agent }
  val manualAgents = input.request.agentSelection.manualAgents
    .ifEmpty { explicitTargets.keys }
  return manualAgents
    .sortedBy(InstallAgent::id)
    .flatMap { agent ->
      explicitTargets[agent]?.map { target -> target.copy(source = InstallAgentTargetSource.MANUAL) }
        ?: (defaultTargets[agent] ?: error("Manual agent '${agent.id}' has no explicit or default target path."))
          .map { default ->
            InstallAgentTarget(
              agent = agent,
              path = default.path,
              source = InstallAgentTargetSource.MANUAL,
            )
          }
    }
}

private fun selectedPlatformSlugs(input: InstallPolicyInput): List<String> {
  return selectedPlatformSlugs(
    selection = input.request.platformPackSelection,
    discoveredSlugs = input.platformPacks.map(InstallPlatformPackSnapshot::slug),
  )
}

private fun selectedPlatformSlugs(selection: PlatformPackSelection, discoveredSlugs: List<String>): List<String> =
  when (selection.mode) {
    PlatformPackSelectionMode.NONE -> emptyList()
    PlatformPackSelectionMode.ALL -> discoveredSlugs
    PlatformPackSelectionMode.SELECTED -> {
      val unknown = selection.selectedSlugs - discoveredSlugs.toSet()
      require(unknown.isEmpty()) {
        "Unknown platform pack selection: ${unknown.sorted().joinToString(", ")}. " +
          "Discovered platform packs: ${discoveredSlugs.joinToString(", ")}."
      }
      discoveredSlugs.filter { slug -> slug in selection.selectedSlugs }
    }
  }
