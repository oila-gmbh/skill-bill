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

internal fun performInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): Pair<List<Path>, List<String>> {
  val agents = detectAgents()
  val installTx = InstallTransaction()
  val internalPlatformSkills = internalPlatformInstallSkills(plan)
  val installPaths = when (plan.kind) {
    SKILL_KIND_ADD_ON -> emptyList()
    SKILL_KIND_AGENT_ADDON -> plan.agentAddonConsumers.map { consumer -> repoRoot.resolve("skills").resolve(consumer) }
    SKILL_KIND_PLATFORM_PACK -> platformPackInstallPaths(plan, repoRoot, internalPlatformSkills)
    else -> listOf(plan.skillPath)
  }
  // F-015: hoist platform-pack manifest discovery out of the per-skill loop. Walking
  // `platform-packs` once is O(packs); doing it per skill in a multi-skill platform-pack scaffold
  // is O(packs * skills). Pass the pre-resolved list down through installSkill so applicablePointers
  // reuses it.
  val packsRoot = repoRoot.resolve("platform-packs")
  val manifests = if (Files.isDirectory(packsRoot)) discoverPlatformPackManifests(packsRoot) else emptyList()
  val context = InstallContext(
    repoRoot = repoRoot,
    manifests = manifests,
    selectedPackSkills = internalPlatformSkills,
  )
  val targets =
    installPaths.flatMap { installPath ->
      installSkill(installPath, agents, transaction = installTx, context = context)
    }
  txn.installTargets += targets
  val notes = when {
    plan.kind == SKILL_KIND_ADD_ON -> listOf(
      ADD_ON_INSTALL_NOTE,
    )
    plan.kind == SKILL_KIND_AGENT_ADDON -> listOf("Agent add-on consumers rendered and installed atomically.")
    agents.isEmpty() -> listOf(
      noAgentsNote(),
    )
    plan.kind == SKILL_KIND_PLATFORM_PACK -> listOf(PLATFORM_PACK_INSTALL_NOTE)
    else -> emptyList()
  }
  return targets to notes
}

internal fun internalPlatformInstallSkills(plan: ScaffoldPlan): List<InstallPlanSkill> {
  if (plan.kind != SKILL_KIND_PLATFORM_PACK) {
    return emptyList()
  }
  return plan.installPaths.mapNotNull { installPath ->
    val internalFor = parseInternalForFrontmatter(installPath.resolve("content.md")) ?: return@mapNotNull null
    InstallPlanSkill(
      name = installPath.fileName.toString(),
      sourceDir = installPath.toAbsolutePath().normalize(),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = plan.platform,
      internalFor = internalFor,
    )
  }
}

internal fun platformPackInstallPaths(
  plan: ScaffoldPlan,
  repoRoot: Path,
  internalPlatformSkills: List<InstallPlanSkill>,
): List<Path> {
  val internalSkillDirs = internalPlatformSkills.map { skill -> skill.sourceDir }.toSet()
  val listedPaths = plan.installPaths.filterNot { installPath ->
    installPath.toAbsolutePath().normalize() in internalSkillDirs
  }
  val parentPaths = internalPlatformSkills
    .mapNotNull(InstallPlanSkill::internalFor)
    .distinct()
    .map { parent -> repoRoot.resolve("skills").resolve(parent) }
  return (listedPaths + parentPaths).distinctBy { path -> path.toAbsolutePath().normalize() }
}

