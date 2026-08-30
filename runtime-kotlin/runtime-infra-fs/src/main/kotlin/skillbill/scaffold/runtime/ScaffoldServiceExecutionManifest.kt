@file:Suppress("TooGenericExceptionCaught", "MaxLineLength")

package skillbill.scaffold.runtime

import skillbill.scaffold.manifest.appendCodeReviewArea
import skillbill.scaffold.manifest.appendExternalAddonManifestRegistration
import skillbill.scaffold.manifest.appendGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.appendReadmeCatalogRow
import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.setDeclaredQualityCheckFile
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.renderNativeAgentBundleStubs
import java.nio.file.Files
import java.nio.file.Path

internal fun snapshotManifest(txn: ScaffoldTransaction, manifestPath: Path) {
  txn.manifestSnapshots += ManifestSnapshot(manifestPath, Files.readAllBytes(manifestPath))
}

internal fun stageSubagentStubs(
  txn: ScaffoldTransaction,
  orchestratorSkillPath: Path,
  specialists: List<String>,
  descriptions: Map<String, String>,
  bodyNames: Set<String>,
): List<Path> {
  val sourcePath = orchestratorSkillPath.resolve("native-agents").resolve("agents.yaml")
  val parentSkill = orchestratorSkillPath.fileName.toString()
  stageFile(txn, sourcePath, renderNativeAgentBundleStubs(specialists, descriptions, bodyNames, parentSkill))
  return listOf(sourcePath)
}

internal fun applyManifestEdits(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  return when (plan.kind) {
    SKILL_KIND_HORIZONTAL -> applyHorizontalManifestEdit(txn, plan, repoRoot)
    SKILL_KIND_CODE_REVIEW_AREA -> applyCodeReviewAreaManifestEdit(txn, plan, repoRoot)
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> applyPlatformOverrideManifestEdit(txn, plan, repoRoot)
    SKILL_KIND_ADD_ON -> applyAddonManifestEdit(txn, plan, repoRoot)
    else -> emptyList()
  }
}

private fun applyHorizontalManifestEdit(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  val readmePath = repoRoot.resolve("README.md")
  if (!Files.exists(readmePath)) {
    return emptyList()
  }
  snapshotManifest(txn, readmePath)
  appendReadmeCatalogRow(readmePath, plan.skillName, effectiveDescription(plan))
  return listOf(readmePath)
}

private fun applyCodeReviewAreaManifestEdit(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
  snapshotManifest(txn, manifestPath)
  val declaredAreaPath = manifestPath.parent.relativize(
    plan.contentFile ?: plan.skillPath.resolve("content.md"),
  ).toString().replace('\\', '/')
  appendCodeReviewArea(manifestPath, plan.area, declaredAreaPath, defaultAreaFocus(plan.area))
  return listOf(manifestPath)
}

private fun applyPlatformOverrideManifestEdit(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  if (!plan.isShelled || plan.family != "quality-check") {
    return emptyList()
  }
  val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
  snapshotManifest(txn, manifestPath)
  val declaredPath = manifestPath.parent.relativize(
    plan.contentFile ?: plan.skillPath.resolve("content.md"),
  ).toString().replace('\\', '/')
  setDeclaredQualityCheckFile(manifestPath, declaredPath)
  return listOf(manifestPath)
}

private fun applyAddonManifestEdit(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  if (plan.addonConsumerSkillDirs.isEmpty()) {
    return emptyList()
  }
  if (plan.isExternalAddon()) {
    return applyExternalAddonManifestEdit(txn, plan)
  }
  val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
  snapshotManifest(txn, manifestPath)
  appendGovernedAddonManifestRegistration(
    manifestPath = manifestPath,
    platform = plan.platform,
    skillRelativeDirs = plan.addonConsumerSkillDirs,
    addonSlug = plan.skillName,
  )
  return listOf(manifestPath)
}

private fun applyExternalAddonManifestEdit(txn: ScaffoldTransaction, plan: ScaffoldPlan): List<Path> {
  val manifestPath = plan.externalAddonManifestPath()
  if (Files.exists(manifestPath)) {
    snapshotManifest(txn, manifestPath)
    appendExternalAddonManifestRegistration(
      manifestPath = manifestPath,
      skillRelativeDirs = plan.addonConsumerSkillDirs,
      addonSlug = plan.skillName,
    )
  } else {
    stageFile(
      txn,
      manifestPath,
      renderExternalAddonManifestRegistration("", plan.addonConsumerSkillDirs, plan.skillName),
    )
  }
  return listOf(manifestPath)
}
