package skillbill.install

import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.scaffold.SHELL_CONTRACT_VERSION
import skillbill.scaffold.discoverPlatformPackManifests
import skillbill.scaffold.loadQualityCheckContent
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.validatePlatformPack
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
      InstallPlanSkill(
        name = skillDir.fileName.toString(),
        sourceDir = skillDir.toAbsolutePath().normalize(),
        kind = InstallPlanSkillKind.BASE,
      )
    }
  require(baseSkills.isNotEmpty()) {
    "Base skills root '$skillsRoot' does not contain any bill-* skills with content.md."
  }
  return baseSkills
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
