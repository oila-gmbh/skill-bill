package skillbill.install.plan

import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.staging.GeneratedSupportPointer
import skillbill.install.staging.InstallContentHashInputs
import skillbill.install.staging.InternalStagingPreparation
import skillbill.install.staging.agentAddonPointersForSkill
import skillbill.install.staging.applicablePointers
import skillbill.install.staging.authoredFilesFor
import skillbill.install.staging.authoredStagingNames
import skillbill.install.staging.computeInstallContentHash
import skillbill.install.staging.generatedSupportPointersFor
import skillbill.install.staging.installedSkillStagingDir
import skillbill.install.staging.installedSkillsCacheRoot
import skillbill.install.staging.prepareInternalStaging
import skillbill.install.staging.validateAgentAddonPointerNamespace
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private data class StagingIntentContext(
  val request: InstallPlanRequest,
  val platformManifests: List<PlatformManifest>,
  val selectedPackSkills: List<InstallPlanSkill>,
  val selectedPlatformManifests: List<PlatformManifest>,
)

internal fun buildInstallStagingIntent(
  request: InstallPlanRequest,
  draftSkills: List<InstallPlanSkill>,
  platformManifests: List<PlatformManifest>,
): InstallStagingIntent {
  val stagingRoot = installedSkillsCacheRoot(request.home)
  val selectedPackSkills = draftSkills.filter { skill ->
    skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null
  }
  val selectedSlugs = selectedPlatformSlugs(draftSkills, platformManifests)
  val selectedManifests = platformManifests.filter { manifest -> manifest.slug in selectedSlugs }
  val context = StagingIntentContext(request, platformManifests, selectedPackSkills, selectedManifests)
  return InstallStagingIntent(
    root = stagingRoot,
    skillPaths = draftSkills.filter { skill -> skill.internalFor == null }
      .map { skill -> buildSkillStagingPathIntent(context, skill, stagingRoot) },
  )
}

private fun buildSkillStagingPathIntent(
  context: StagingIntentContext,
  skill: InstallPlanSkill,
  stagingRoot: Path,
): InstallStagingPathIntent {
  val request = context.request
  val pointers = applicablePointers(request.repoRoot, skill.sourceDir, context.platformManifests)
  val supportPointers = generatedSupportPointersFor(
    repoRoot = request.repoRoot,
    sourceSkillDir = skill.sourceDir,
    skillName = skill.name,
    skillsRoot = request.targetPaths.skillsRoot,
    selectedPlatformManifests = context.selectedPlatformManifests,
  )
  val internal = prepareInternalStaging(
    InternalStagingPreparation(
      repoRoot = request.repoRoot,
      parentSourceDir = skill.sourceDir,
      parentSkillName = skill.name,
      skillsRoot = request.targetPaths.skillsRoot,
      selectedPackSkills = context.selectedPackSkills,
      platformManifests = context.platformManifests,
      selectedPlatformManifests = context.selectedPlatformManifests,
      parentSupportPointers = supportPointers,
      parentPointerNames = pointers.map { (_, pointer) -> pointer.name }.toSet(),
    ),
  )
  validatePointerInputs(request.repoRoot, skill.sourceDir, pointers, internal.supportPointers)
  val authored = authoredFilesFor(skill.sourceDir, pointers, internal.supportPointers, internal.sidecarNames)
  val addonPointers = agentAddonPointersForSkill(request.repoRoot, skill.name)
  validateAgentAddonPointerNamespace(
    skill.name,
    authoredStagingNames(skill.sourceDir, authored) + internal.sidecarNames + pointers.map { it.second.name } +
      internal.supportPointers.map { it.name } + listOf("SKILL.md", ".content-hash"),
    addonPointers,
  )
  val contentHash = computeInstallContentHash(
    InstallContentHashInputs(
      sourceSkillDir = skill.sourceDir,
      authored = authored,
      applicablePointers = pointers,
      generatedSupportPointers = internal.supportPointers,
      internalChildren = internal.children,
      agentAddonPointers = addonPointers,
    ),
  )
  return InstallStagingPathIntent(
    skillName = skill.name,
    sourceDir = skill.sourceDir,
    stagingRoot = stagingRoot,
    stagingDir = installedSkillStagingDir(request.home, skill.sourceDir, contentHash),
    contentHash = contentHash,
  )
}

private fun validatePointerInputs(
  repoRoot: Path,
  sourceSkillDir: Path,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
  supportPointers: List<GeneratedSupportPointer>,
) {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val realRepoRoot = repoRoot.toRealPath()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  pointers.forEach { (manifest, spec) ->
    val pointerFile = manifest.packRoot.toAbsolutePath().normalize()
      .resolve(spec.skillRelativeDir).normalize().resolve(spec.name).normalize()
    val targetFile = resolvedRepoRoot.resolve(spec.target).normalize()
    validatePointerTarget(spec.name, targetFile, pointerFile, resolvedRepoRoot, realRepoRoot)
  }
  supportPointers.forEach { pointer ->
    val targetFile = pointer.target.toAbsolutePath().normalize()
    val pointerFile = resolvedSource.resolve(pointer.name).normalize()
    require(pointerFile.startsWith(resolvedSource)) {
      "Supporting pointer '${pointer.name}' staging path '$pointerFile' escapes source skill dir '$resolvedSource'."
    }
    require(targetFile.startsWith(resolvedRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot'."
    }
    require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      "Supporting pointer '${pointer.name}' targets '$targetFile' which does not exist."
    }
    require(targetFile.toRealPath().startsWith(realRepoRoot)) {
      "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot' " +
        "through its real path."
    }
    require(pointerFile != targetFile) {
      "Supporting pointer '${pointer.name}' resolves to itself at '$targetFile'."
    }
  }
}

private fun validatePointerTarget(
  name: String,
  targetFile: Path,
  pointerFile: Path,
  repoRoot: Path,
  realRepoRoot: Path,
) {
  require(targetFile.startsWith(repoRoot)) { "Pointer '$name' target '$targetFile' escapes repoRoot '$repoRoot'." }
  require(pointerFile.startsWith(repoRoot)) { "Pointer '$name' path '$pointerFile' escapes repoRoot '$repoRoot'." }
  require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
    "Pointer '$name' target '$targetFile' does not exist as a regular file."
  }
  require(targetFile.toRealPath().startsWith(realRepoRoot)) {
    "Pointer '$name' target '$targetFile' escapes repoRoot '$repoRoot' through its real path."
  }
  require(pointerFile != targetFile) { "Pointer '$name' resolves to itself at '$targetFile'." }
}
