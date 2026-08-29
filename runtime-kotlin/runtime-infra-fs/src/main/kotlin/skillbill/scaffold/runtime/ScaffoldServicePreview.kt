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

internal fun previewCreatedFiles(plan: ScaffoldPlan): List<Path> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> previewPlatformPackCreatedFiles(plan) + previewSubagentStubFiles(plan)
  SKILL_KIND_ADD_ON -> if (plan.isExternalAddon() && !Files.exists(plan.externalAddonManifestPath())) {
    listOf(plan.skillFile, plan.externalAddonManifestPath())
  } else {
    listOf(plan.skillFile)
  }
  SKILL_KIND_AGENT_ADDON -> listOf(plan.skillFile, requireNotNull(plan.contentFile))
  else -> buildList {
    plan.contentFile?.let(::add)
    addAll(previewSubagentStubFiles(plan))
  }
}

internal fun previewManifestEdits(plan: ScaffoldPlan, repoRoot: Path): List<Path> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> listOf(plan.manifestPath ?: platformPackManifestPath(repoRoot, plan.platform))
  SKILL_KIND_ADD_ON -> if (plan.addonConsumerSkillDirs.isEmpty()) {
    emptyList()
  } else if (plan.isExternalAddon()) {
    listOf(plan.externalAddonManifestPath())
  } else {
    listOf(platformPackManifestPath(repoRoot, plan.platform))
  }
  SKILL_KIND_CODE_REVIEW_AREA, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED ->
    listOf(platformPackManifestPath(repoRoot, plan.platform))
  SKILL_KIND_HORIZONTAL -> {
    val readmePath = repoRoot.resolve("README.md")
    if (Files.exists(readmePath)) listOf(readmePath) else emptyList()
  }
  else -> emptyList()
}

internal fun previewManifestPreviews(plan: ScaffoldPlan, repoRoot: Path): Map<Path, String> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> {
    val manifestPath = plan.manifestPath ?: platformPackManifestPath(repoRoot, plan.platform)
    val baselineSkillPath = plan.baselineSkillPath ?: error("Platform pack plan missing baseline skill path.")
    val qualityCheckSkillPath =
      plan.qualityCheckSkillPath ?: error("Platform pack plan missing quality-check skill path.")
    mapOf(manifestPath to renderPlatformPackManifestContent(plan, repoRoot, baselineSkillPath, qualityCheckSkillPath))
  }
  SKILL_KIND_ADD_ON -> {
    if (plan.addonConsumerSkillDirs.isEmpty()) {
      emptyMap()
    } else if (plan.isExternalAddon()) {
      val manifestPath = plan.externalAddonManifestPath()
      val current = if (Files.exists(manifestPath)) Files.readString(manifestPath) else ""
      mapOf(
        manifestPath to renderExternalAddonManifestRegistration(
          text = current,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        ),
      )
    } else {
      val manifestPath = platformPackManifestPath(repoRoot, plan.platform)
      mapOf(
        manifestPath to renderGovernedAddonManifestRegistration(
          text = Files.readString(manifestPath),
          platform = plan.platform,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        ),
      )
    }
  }
  SKILL_KIND_HORIZONTAL -> {
    val readmePath = repoRoot.resolve("README.md")
    if (Files.exists(readmePath)) {
      mapOf(
        readmePath to renderReadmeCatalogRow(
          text = Files.readString(readmePath),
          skillName = plan.skillName,
          description = effectiveDescription(plan),
        ),
      )
    } else {
      emptyMap()
    }
  }
  else -> emptyMap()
}

// SKILL-52.1 subtask 3 (AC1): `validateScaffold` and `plannedAuthoringTarget` now live on
// `FileSystemScaffoldRepoValidation`. Callsites delegate to
// `scaffoldRepoValidation.validateScaffold(...)`.

