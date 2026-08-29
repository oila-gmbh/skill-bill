package skillbill.scaffold.runtime

import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.renderGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.renderReadmeCatalogRow
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import java.nio.file.Files
import java.nio.file.Path

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
