@file:Suppress(
  "TooManyFunctions",
  "LongMethod",
  "ComplexMethod",
  "NestedBlockDepth",
  "ReturnCount",
  "ThrowsCount",
  "TooGenericExceptionCaught",
  "MaxLineLength",
)

package skillbill.scaffold.runtime

import skillbill.error.SkillAlreadyExistsError
import skillbill.scaffold.manifest.appendCodeReviewArea
import skillbill.scaffold.manifest.appendExternalAddonManifestRegistration
import skillbill.scaffold.manifest.appendGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.appendReadmeCatalogRow
import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.setDeclaredQualityCheckFile
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderAddonBody
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.rendering.renderNativeAgentBundleStubs
import java.nio.file.Files
import java.nio.file.Path

internal fun createPlatformPack(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): ScaffoldExecutionResult {
  val manifestPath = plan.manifestPath ?: error("Platform pack plan missing manifest path.")
  val baselineSkillPath = plan.baselineSkillPath ?: error("Platform pack plan missing baseline skill path.")
  val qualityCheckSkillPath =
    plan.qualityCheckSkillPath ?: error("Platform pack plan missing quality-check skill path.")
  stageFile(
    txn,
    manifestPath,
    renderPlatformPackManifestContent(
      plan,
      repoRoot,
      baselineSkillPath,
      qualityCheckSkillPath,
    ),
  )
  val symlinks = stagePlatformPackSkills(txn, plan, repoRoot, baselineSkillPath, qualityCheckSkillPath)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = baselineSkillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
      bodyNames = plan.bodyBasedSubagents,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = listOf(manifestPath),
    symlinks = symlinks,
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

internal fun stageSingleScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): ScaffoldExecutionResult {
  if (plan.kind == SKILL_KIND_AGENT_ADDON) {
    stageFile(txn, plan.skillFile, renderAgentAddonManifest(plan))
    stageFile(txn, requireNotNull(plan.contentFile), plan.contentBody ?: "# ${plan.skillName}\n")
  } else if (plan.kind == SKILL_KIND_ADD_ON) {
    stageFile(txn, plan.skillFile, renderAddonBody(plan.skillName, plan.description, plan.addonBody))
  } else {
    plan.contentFile?.let { content ->
      val contentText = if (plan.kind == SKILL_KIND_CODE_REVIEW_AREA || plan.isShelled) {
        renderDeclaredPackContentSheet(plan)
      } else {
        renderContentSheet(plan)
      }
      stageFile(txn, content, contentText)
    }
  }
  val manifestEdits = applyManifestEdits(txn, plan, repoRoot)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = plan.skillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
      bodyNames = plan.bodyBasedSubagents,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = manifestEdits,
    symlinks = emptyList(),
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

internal fun renderAgentAddonManifest(plan: ScaffoldPlan): String = buildString {
  appendLine("contract_version: \"1.0\"")
  appendLine("slug: ${plan.skillName}")
  appendLine("description: ${yamlScalar(plan.description)}")
  appendLine("agent_ids:")
  plan.agentIds.forEach { appendLine("  - $it") }
  appendLine("consumers:")
  plan.agentAddonConsumers.forEach { appendLine("  - $it") }
}

internal fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

internal fun renderContentSheet(plan: ScaffoldPlan): String = renderContentBody(
  skillContext(plan),
  description = effectiveDescription(plan),
  contentBody = plan.contentBody,
)

internal fun renderDeclaredPackContentSheet(plan: ScaffoldPlan): String = renderContentBody(
  skillContext(plan),
  description = effectiveDescription(plan),
  contentBody = plan.contentBody,
)

internal fun skillContext(plan: ScaffoldPlan): TemplateContext = TemplateContext(
  skillName = plan.skillName,
  family = plan.family,
  platform = plan.platform,
  area = plan.area,
  displayName = plan.displayName.ifBlank { deriveDisplayName(plan.platform) },
)

internal fun areaFocus(plan: ScaffoldPlan): String =
  plan.area.takeIf { it.isNotBlank() }?.let(::defaultAreaFocus).orEmpty()

internal fun effectiveDescription(plan: ScaffoldPlan): String {
  val context = skillContext(plan)
  return plan.description.ifBlank { inferSkillDescription(context, areaFocus(plan)) }
}

internal fun stageFile(txn: ScaffoldTransaction, path: Path, content: String) {
  if (Files.exists(path)) {
    throw SkillAlreadyExistsError(
      "Skill target '$path' already exists. Remove it or pick a new name before retrying.",
    )
  }
  val parents = mutableListOf<Path>()
  var cursor = path.parent
  while (cursor != null && !Files.exists(cursor)) {
    parents.add(cursor)
    cursor = cursor.parent
  }
  parents.asReversed().forEach { dir ->
    Files.createDirectories(dir)
    txn.createdDirs.add(dir)
  }
  Files.writeString(path, content)
  txn.createdPaths.add(path)
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

internal fun snapshotManifest(txn: ScaffoldTransaction, manifestPath: Path) {
  txn.manifestSnapshots += ManifestSnapshot(manifestPath, Files.readAllBytes(manifestPath))
}

internal fun applyManifestEdits(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  return when (plan.kind) {
    SKILL_KIND_HORIZONTAL -> {
      val readmePath = repoRoot.resolve("README.md")
      if (!Files.exists(readmePath)) {
        emptyList()
      } else {
        snapshotManifest(txn, readmePath)
        appendReadmeCatalogRow(readmePath, plan.skillName, effectiveDescription(plan))
        listOf(readmePath)
      }
    }
    SKILL_KIND_CODE_REVIEW_AREA -> {
      val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
      snapshotManifest(txn, manifestPath)
      val declaredAreaPath = manifestPath.parent.relativize(
        plan.contentFile ?: plan.skillPath.resolve("content.md"),
      ).toString().replace('\\', '/')
      appendCodeReviewArea(manifestPath, plan.area, declaredAreaPath, defaultAreaFocus(plan.area))
      listOf(manifestPath)
    }
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
      if (plan.isShelled && plan.family == "quality-check") {
        val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
        snapshotManifest(txn, manifestPath)
        val declaredPath = manifestPath.parent.relativize(
          plan.contentFile ?: plan.skillPath.resolve("content.md"),
        ).toString().replace('\\', '/')
        setDeclaredQualityCheckFile(manifestPath, declaredPath)
        listOf(manifestPath)
      } else {
        emptyList()
      }
    }
    SKILL_KIND_ADD_ON -> {
      if (plan.addonConsumerSkillDirs.isEmpty()) {
        emptyList()
      } else if (plan.isExternalAddon()) {
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
        listOf(manifestPath)
      } else {
        val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
        snapshotManifest(txn, manifestPath)
        appendGovernedAddonManifestRegistration(
          manifestPath = manifestPath,
          platform = plan.platform,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        )
        listOf(manifestPath)
      }
    }
    else -> emptyList()
  }
}
