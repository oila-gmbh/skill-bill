@file:Suppress("ThrowsCount")

package skillbill.install.plan

import skillbill.error.InvalidInternalSkillClassificationError
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadQualityCheckContent
import skillbill.scaffold.platformpack.validatePlatformPack
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun discoverPlatformManifests(platformPacksRoot: Path): List<PlatformManifest> =
  if (Files.isDirectory(platformPacksRoot)) {
    discoverPlatformPackManifests(platformPacksRoot)
  } else {
    emptyList()
  }

internal fun discoverBaseSkills(skillsRoot: Path): List<InstallPlanSkill> {
  if (!Files.isDirectory(skillsRoot)) {
    throw java.io.FileNotFoundException("Base skills root '$skillsRoot' does not exist or is not a directory.")
  }
  val candidateSkillDirs = Files.list(skillsRoot).use { stream ->
    stream
      .filter { skillDir -> Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS) }
      .filter { skillDir -> skillDir.fileName.toString().startsWith("bill-") }
      .toList()
      .sortedBy { skillDir -> skillDir.fileName.toString() }
  }
  val missingContent = candidateSkillDirs
    .filterNot { skillDir -> Files.isRegularFile(skillDir.resolve("content.md"), LinkOption.NOFOLLOW_LINKS) }
  require(missingContent.isEmpty()) {
    "Base skills root '$skillsRoot' contains bill-* skill directories without content.md: " +
      missingContent.joinToString(", ") { skillDir -> skillDir.fileName.toString() }
  }
  val baseSkills = candidateSkillDirs
    .map { skillDir ->
      val contentFile = skillDir.resolve("content.md")
      InstallPlanSkill(
        name = skillDir.fileName.toString(),
        sourceDir = skillDir.toAbsolutePath().normalize(),
        kind = InstallPlanSkillKind.BASE,
        // SKILL-102 subtask 1 (PD1): carry the internal-for classification onto the install plan
        // so staging can render sidecars and skip agent skills_dir links. Classification rules
        // are validated once the full skill set is known (see validateInstallPlanInternalSkills).
        internalFor = parseInternalForFrontmatter(contentFile),
      )
    }
  require(baseSkills.isNotEmpty()) {
    "Base skills root '$skillsRoot' does not contain any bill-* skills with content.md."
  }
  return baseSkills
}

/**
 * SKILL-102 subtask 1 (PD1): enforce the internal-skill classification rules across the full
 * install-plan skill set (base + materialized platform-pack skills) once every name is known.
 *
 * Rules mirror [skillbill.scaffold.authoring.validateInternalSkillClassification] so the install
 * seam and the authoring/validation seam fail identically. Throws
 * [InvalidInternalSkillClassificationError] on any violation with an actionable message naming
 * the offending skill, the declared parent, and the rule violated.
 */
internal fun validateInstallPlanInternalSkills(skills: List<InstallPlanSkill>) {
  val byName = skills.associateBy { skill -> skill.name }
  skills.forEach { skill ->
    val declaredParent = skill.internalFor ?: return@forEach
    val contentFile = skill.sourceDir.resolve("content.md")
    if (declaredParent.isBlank()) {
      throw InvalidInternalSkillClassificationError(
        "$contentFile: internal skill '${skill.name}' declares parent via 'internal-for:' with an " +
          "empty value; the value must be the name of another discovered skill.",
      )
    }
    if (declaredParent == skill.name) {
      throw InvalidInternalSkillClassificationError(
        "$contentFile: internal skill '${skill.name}' declares parent '$declaredParent' which is " +
          "the skill itself; an internal skill's parent must be a different discovered skill.",
      )
    }
    val parent = byName[declaredParent]
    if (parent == null) {
      throw InvalidInternalSkillClassificationError(
        "$contentFile: internal skill '${skill.name}' declares parent '$declaredParent' which is " +
          "not a discovered skill.",
      )
    }
    if (parent.internalFor != null) {
      throw InvalidInternalSkillClassificationError(
        "$contentFile: internal skill '${skill.name}' declares parent '$declaredParent' which is " +
          "itself an internal skill (chained internal-for is not allowed; depth is 1).",
      )
    }
  }
}

internal fun platformSkills(manifest: PlatformManifest): List<InstallPlanSkill> {
  validatePlatformPack(manifest, SHELL_CONTRACT_VERSION)
  manifest.declaredQualityCheckFile?.let { loadQualityCheckContent(manifest) }
  val contentFiles = listOfNotNull(
    manifest.declaredFiles.baseline,
    manifest.declaredQualityCheckFile,
  ) + manifest.declaredFiles.areas.values
  return contentFiles
    .map { contentFile -> platformSkillDir(manifest, contentFile) }
    .sortedBy { skillDir -> skillDir.fileName.toString() }
    .map { skillDir ->
      InstallPlanSkill(
        name = skillDir.fileName.toString(),
        sourceDir = skillDir,
        kind = InstallPlanSkillKind.PLATFORM_PACK,
        platformSlug = manifest.slug,
      )
    }
}

private fun platformSkillDir(manifest: PlatformManifest, contentFile: Path): Path {
  val resolvedPackRoot = manifest.packRoot.toAbsolutePath().normalize()
  val resolvedContentFile = contentFile.toAbsolutePath().normalize()
  require(resolvedContentFile.startsWith(resolvedPackRoot)) {
    "Platform pack '${manifest.slug}' declared content file '$resolvedContentFile' escapes packRoot " +
      "'$resolvedPackRoot'."
  }
  val realPackRoot = manifest.packRoot.toRealPath()
  val realContentFile = contentFile.toRealPath()
  require(realContentFile.startsWith(realPackRoot)) {
    "Platform pack '${manifest.slug}' declared content file '$resolvedContentFile' escapes packRoot " +
      "'$resolvedPackRoot' through real path '$realContentFile'."
  }
  return resolvedContentFile.parent
}
