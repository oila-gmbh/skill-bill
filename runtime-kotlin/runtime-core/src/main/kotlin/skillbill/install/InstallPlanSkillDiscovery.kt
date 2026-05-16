package skillbill.install

import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.scaffold.discoverPlatformPacks
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun discoverPlatformManifests(platformPacksRoot: Path): List<PlatformManifest> =
  if (Files.isDirectory(platformPacksRoot)) {
    discoverPlatformPacks(platformPacksRoot)
  } else {
    emptyList()
  }

internal fun selectedPlatformSlugs(request: InstallPlanRequest, manifests: List<PlatformManifest>): List<String> {
  val discoveredSlugs = manifests.map(PlatformManifest::slug)
  return when (request.platformPackSelection.mode) {
    PlatformPackSelectionMode.NONE -> emptyList()
    PlatformPackSelectionMode.ALL -> discoveredSlugs
    PlatformPackSelectionMode.SELECTED -> {
      val unknown = request.platformPackSelection.selectedSlugs - discoveredSlugs.toSet()
      require(unknown.isEmpty()) {
        "Unknown platform pack selection: ${unknown.sorted().joinToString(", ")}. " +
          "Discovered platform packs: ${discoveredSlugs.joinToString(", ")}."
      }
      discoveredSlugs.filter { slug -> slug in request.platformPackSelection.selectedSlugs }
    }
  }
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

internal fun requireUniqueSkillNames(skills: List<InstallPlanSkill>) {
  val duplicates = skills
    .groupBy(InstallPlanSkill::name)
    .filterValues { matchingSkills -> matchingSkills.size > 1 }
  require(duplicates.isEmpty()) {
    duplicates.entries.joinToString(
      prefix = "Install plan contains duplicate skill name(s): ",
      separator = "; ",
    ) { (name, matchingSkills) ->
      "$name at ${matchingSkills.joinToString(", ") { skill -> skill.sourceDir.toString() }}"
    }
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
