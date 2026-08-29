package skillbill.scaffold.runtime

import skillbill.agentaddon.AgentAddonSchemaValidator
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingPlatformPackError
import skillbill.error.ScaffoldRollbackError
import skillbill.error.SkillAlreadyExistsError
import skillbill.error.UnknownPreShellFamilyError
import skillbill.error.UnknownSkillKindError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTransaction
import skillbill.install.plan.InstallContext
import skillbill.install.plan.detectAgents
import skillbill.install.plan.installSkill
import skillbill.install.plan.uninstallTargets
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.manifest.appendCodeReviewArea
import skillbill.scaffold.manifest.appendExternalAddonManifestRegistration
import skillbill.scaffold.manifest.appendGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.appendReadmeCatalogRow
import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.renderGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.renderReadmeCatalogRow
import skillbill.scaffold.manifest.setDeclaredQualityCheckFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.scaffold.sharedContractNote
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderAddonBody
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.rendering.renderNativeAgentBundleStubs
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.payload.detectKind as policyDetectKind
import skillbill.scaffold.payload.optionalSpecialistSubagents as policyOptionalSpecialistSubagents
import skillbill.scaffold.payload.rejectBaselineLayersForNonPlatformPack as policyRejectBaselineLayersForNonPlatformPack
import skillbill.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.scaffold.payload.requireStringMap as requireString
import skillbill.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.scaffold.payload.resolvePlatformPackDefaults as policyResolvePlatformPackDefaults
import skillbill.scaffold.payload.resolvePlatformPackSelection as policyResolvePlatformPackSelection
import skillbill.scaffold.payload.validatePayloadVersion as policyValidatePayloadVersion
import skillbill.scaffold.policy.platformpack.buildPlatformPackInstallPaths as policyBuildPlatformPackInstallPaths
import skillbill.scaffold.policy.platformpack.platformPackNotes as policyPlatformPackNotes
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifestContent as policyRenderPlatformPackManifestContent
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.payload.requireStringListPayload

internal fun executeScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldExecutionResult {
  val execution =
    when (plan.kind) {
      SKILL_KIND_PLATFORM_PACK -> createPlatformPack(txn, plan, repoRoot)
      else -> stageSingleScaffold(txn, plan, repoRoot)
    }
  adapters.validateScaffold(plan, repoRoot)
  val (installTargets, installNotes) = performInstall(txn, plan, repoRoot)
  return execution.copy(
    installTargets = installTargets,
    notes = execution.notes + installNotes + subagentEmissionNotes(plan),
  )
}

internal fun resolveRepoRoot(payload: Map<String, Any?>): Path {
  val repoRootRaw = payload["repo_root"] as? String ?: return defaultRepoRoot()
  if (repoRootRaw.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'repo_root' must be a non-empty string when provided.",
    )
  }
  return Path.of(repoRootRaw).toAbsolutePath().normalize()
}

internal fun planScaffold(
  payload: Map<String, Any?>,
  repoRoot: Path,
  kind: String,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan = when (kind) {
  SKILL_KIND_HORIZONTAL -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planHorizontal(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planPlatformOverridePiloted(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_PACK -> planPlatformPack(payload, repoRoot, adapters)
  SKILL_KIND_CODE_REVIEW_AREA -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planCodeReviewArea(payload, repoRoot)
  }
  SKILL_KIND_ADD_ON -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planAddOn(payload, repoRoot, adapters)
  }
  SKILL_KIND_AGENT_ADDON -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planAgentAddon(payload, repoRoot)
  }
  else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
}

internal fun planHorizontal(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val name = requireString(payload, "name")
  val skillPath = repoRoot.resolve("skills").resolve(name)
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_HORIZONTAL)
  return ScaffoldPlan(
    kind = SKILL_KIND_HORIZONTAL,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "horizontal",
    platform = "",
    area = "",
    isShelled = false,
    notes = emptyList(),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

internal fun planPlatformOverridePiloted(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val family = requireString(payload, "family")
  val name = canonicalName(payload, defaultName = defaultPlatformOverrideName(platform, family))
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED)
  val isShelled = family in SHELLED_FAMILIES
  val notes = mutableListOf<String>()
  if (!isShelled) {
    if (family !in PRE_SHELL_FAMILIES) {
      val replacement = if (family == "feature-" + "implement") {
        " Use 'feature-task' instead."
      } else {
        ""
      }
      throw UnknownPreShellFamilyError(
        "Scaffold payload declares pre-shell family '$family' " +
          "that is not in the registered set $PRE_SHELL_FAMILIES.$replacement",
      )
    }
    val skillPath = repoRoot.resolve("skills").resolve(platform).resolve(name)
    notes +=
      "Pre-shell family '$family' placed at '${repoRoot.relativize(skillPath)}'; " +
      "will move when the family is piloted onto the shell+content contract."
    return ScaffoldPlan(
      kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
      skillName = name,
      skillPath = skillPath,
      skillFile = skillPath.resolve("SKILL.md"),
      contentFile = skillPath.resolve("content.md"),
      family = family,
      platform = platform,
      area = "",
      isShelled = false,
      notes = notes,
      contentBody = payload["content_body"] as? String,
      subagentSpecialists = subagents.specialists,
      subagentsSuppressed = subagents.suppressed,
    )
  }

  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a skill into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve(family).resolve(name)
  notes +=
    "Author skill instructions only in sibling `content.md` files. " +
    "Generated `SKILL.md` wrappers and platform pointer files are render/install output."
  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = family,
    platform = platform,
    area = "",
    isShelled = true,
    notes = notes,
    displayName = pack.displayName ?: deriveDisplayName(platform),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

internal fun planPlatformPack(
  payload: Map<String, Any?>,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  rejectPlatformPackSubagentOverrides(payload)
  val defaults = policyResolvePlatformPackDefaults(payload, platform)
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (Files.exists(packRoot)) {
    throw SkillAlreadyExistsError(
      "Platform pack target '$packRoot' already exists. " +
        "Remove it or pick a new platform slug before retrying.",
    )
  }
  val baselineName = canonicalName(payload, defaultName = "bill-$platform-code-review")
  val qualityCheckName = "bill-$platform-code-check"
  val baselineLayers = adapters.optionalBaselineLayers(payload, repoRoot, platform)
  val selection = policyResolvePlatformPackSelection(payload)
  val selectedAreas = selection.selectedAreas
  val specialistNames =
    selectedAreas.associateWith { area -> "bill-$platform-code-review-$area" }
  val specialistPaths =
    selectedAreas.associateWith { area ->
      packRoot.resolve("code-review").resolve(specialistNames.getValue(area))
    }
  val specialistMetadata =
    selectedAreas.associateWith { area ->
      specialistFocus(defaults.displayName, area, defaults.strongSignals)
    }
  val platformPackSubagents = selectedAreas.map { area -> specialistNames.getValue(area) }
  val platformPackSubagentDescriptions = selectedAreas.associate { area ->
    val name = specialistNames.getValue(area)
    val description =
      "${defaults.displayName} ${area.replace('-', ' ')} specialist — " +
        "${specialistMetadata.getValue(area)}."
    name to description
  }
  val notes = policyPlatformPackNotes(platform, defaults.presetUsed, selectedAreas)

  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_PACK,
    skillName = baselineName,
    skillPath = packRoot,
    skillFile = packRoot.resolve("code-review").resolve(baselineName).resolve("SKILL.md"),
    contentFile = packRoot.resolve("code-review").resolve(baselineName).resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = "",
    isShelled = true,
    notes = notes,
    displayName = defaults.displayName,
    description = requireStringOrDefault(payload, "description", ""),
    manifestPath = packRoot.resolve("platform.yaml"),
    routingSignals = defaults.strongSignals,
    tieBreakers = defaults.tieBreakers,
    specialistAreas = selectedAreas,
    specialistAreaMetadata = specialistMetadata,
    specialistSkillNames = specialistNames,
    specialistSkillPaths = specialistPaths,
    baselineSkillName = baselineName,
    baselineSkillPath = packRoot.resolve("code-review").resolve(baselineName),
    qualityCheckSkillName = qualityCheckName,
    qualityCheckSkillPath = packRoot.resolve("quality-check").resolve(qualityCheckName),
    installPaths = policyBuildPlatformPackInstallPaths(
      packRoot = packRoot,
      baselineName = baselineName,
      qualityCheckName = qualityCheckName,
      specialistPaths = specialistPaths,
      selectedAreas = selectedAreas,
    ),
    contentBody = payload["content_body"] as? String,
    baselineLayers = baselineLayers,
    subagentSpecialists = platformPackSubagents,
    subagentDescriptions = platformPackSubagentDescriptions,
    subagentsSuppressed = false,
  )
}

internal fun rejectPlatformPackSubagentOverrides(payload: Map<String, Any?>) {
  val field = listOf("subagent_specialists", "no_subagents").firstOrNull(payload::containsKey) ?: return
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field '$field' is not supported for kind 'platform-pack'; " +
      "the review structure standard requires exactly one manifest-derived native agent per declared specialist.",
  )
}

internal fun specialistFocus(displayName: String, area: String, routingSignals: List<String>): String =
  "$displayName ${defaultAreaFocus(area)} across ${routingSignals.joinToString(", ")} signals"

// SKILL-52.1 subtask 3 (AC1): `optionalBaselineLayers`, `validateBaselineLayerPayloadReferences`,
// and `validateBaselineLayerModeSupport` now live on `FileSystemScaffoldRepoValidation`.
// Callsites below delegate to `scaffoldRepoValidation.optionalBaselineLayers(...)`.

internal fun planCodeReviewArea(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_CODE_REVIEW_AREA)
  val platform = requireString(payload, "platform")
  val area = requireString(payload, "area")
  if (area !in APPROVED_CODE_REVIEW_AREAS) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload declares code-review area '$area' that is not in the approved set $APPROVED_CODE_REVIEW_AREAS.",
    )
  }
  val name = canonicalName(payload, defaultName = "bill-$platform-code-review-$area")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a code-review area to it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve("code-review").resolve(name)
  return ScaffoldPlan(
    kind = SKILL_KIND_CODE_REVIEW_AREA,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = area,
    isShelled = true,
    notes = listOf(sharedContractNote()),
    displayName = pack.displayName ?: deriveDisplayName(platform),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
  )
}

internal fun planAddOn(payload: Map<String, Any?>, repoRoot: Path, adapters: ScaffoldAdapterSeams): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_ADD_ON)
  val name = requireString(payload, "name")
  val platform = requireString(payload, "platform")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (!Files.isRegularFile(packRoot.resolve("platform.yaml"))) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a governed add-on into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val externalLocationPath = optionalAddonLocationPath(payload, repoRoot)
  val addonDir = externalLocationPath ?: packRoot.resolve("addons")
  val skillFile = addonDir.resolve("$name.md")
  val addOnFile = displayPath(repoRoot, skillFile)
  return ScaffoldPlan(
    kind = SKILL_KIND_ADD_ON,
    skillName = name,
    skillPath = addonDir,
    skillFile = skillFile,
    contentFile = null,
    family = "add-on",
    platform = platform,
    area = "",
    isShelled = false,
    notes = listOf("After creation, edit the generated add-on body in `$addOnFile`, then validate and render."),
    description = requireStringOrDefault(payload, "description", ""),
    addonBody = payload["body"] as? String,
    addonConsumerSkillDirs = adapters.resolveAddonConsumerSkillDirs(payload, packRoot, pack),
    externalAddonLocationPath = externalLocationPath,
  )
}

internal fun planAgentAddon(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val slug = requireString(payload, "slug")
  val description = requireString(payload, "description")
  val agentIds = requireStringListPayload(payload["agent_ids"], "agent_ids")
  val consumers = requireStringListPayload(payload["consumers"], "consumers")
  AgentAddonSchemaValidator().validate(
    mapOf(
      "contract_version" to "1.0",
      "slug" to slug,
      "description" to description,
      "agent_ids" to agentIds,
      "consumers" to consumers,
    ),
    "agent-addon scaffold payload",
  )
  if (description != description.trim() || '\n' in description || '\r' in description) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'description' must be trimmed and single-line for an agent add-on.",
    )
  }
  agentIds.forEach { id ->
    try {
      InstallAgent.fromId(id)
    } catch (error: IllegalArgumentException) {
      throw InvalidScaffoldPayloadError(error.message ?: "Unknown agent '$id'.", error)
    }
  }
  consumers.forEach { id ->
    try {
      AgentAddonConsumer.fromId(id)
    } catch (error: IllegalArgumentException) {
      throw InvalidScaffoldPayloadError(error.message ?: "Unknown agent add-on consumer '$id'.", error)
    }
  }
  val agentAddonsRoot = repoRoot.resolve("agent-addons").toAbsolutePath().normalize()
  val root = agentAddonsRoot.resolve(slug).normalize()
  if (!root.startsWith(agentAddonsRoot)) {
    throw InvalidScaffoldPayloadError("Scaffold payload field 'slug' escapes the agent-addons root.")
  }
  return ScaffoldPlan(
    kind = SKILL_KIND_AGENT_ADDON,
    skillName = slug,
    skillPath = root,
    skillFile = root.resolve("agent-addon.yaml"),
    contentFile = root.resolve("content.md"),
    family = "agent-addon",
    platform = "",
    area = "",
    isShelled = false,
    notes = listOf("Agent add-on '$slug' will be delivered to its declared consumers during install rendering."),
    description = description,
    contentBody = payload["content_body"] as? String,
    agentIds = agentIds,
    agentAddonConsumers = consumers,
  )
}

// SKILL-52.1 subtask 3 (AC1): `resolveAddonConsumerSkillDirs` and `validateAddonConsumerSkillDir`
// now live on `FileSystemScaffoldSourceLoader`. Callsites delegate to
// `scaffoldSourceLoader.resolveAddonConsumerSkillDirs(...)`.
