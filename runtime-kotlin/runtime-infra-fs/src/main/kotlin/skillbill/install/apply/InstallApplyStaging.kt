package skillbill.install.apply

import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.RenderedSkill
import skillbill.install.staging.applicablePointers
import skillbill.install.staging.authoredFilesFor
import skillbill.install.staging.computeInstallContentHash
import skillbill.install.staging.discoverInternalSidecarTargets
import skillbill.install.staging.generatedSupportPointersFor
import skillbill.install.staging.installedSkillStagingDir
import skillbill.install.staging.installedSkillsCacheRoot
import skillbill.install.staging.isReusableInstallStaging
import skillbill.install.staging.reuseInstallStaging
import skillbill.install.staging.stageInstalledSkill
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

private val plannedContentHashRegex = Regex("[0-9a-f]{16}")

internal fun plannedStagingIntent(plan: InstallPlan, skill: InstallPlanSkill): InstallStagingPathIntent =
  plan.staging.skillPaths.singleOrNull { intent ->
    intent.skillName == skill.name &&
      intent.sourceDir.toAbsolutePath().normalize() == skill.sourceDir.toAbsolutePath().normalize()
  } ?: error("Install plan is missing planned staging intent for skill '${skill.name}' at '${skill.sourceDir}'.")

internal fun validatedPlannedStaging(
  plan: InstallPlan,
  skill: InstallPlanSkill,
  intent: InstallStagingPathIntent,
  platformManifests: List<PlatformManifest>,
): RenderedSkill {
  val expectedRoot = installedSkillsCacheRoot(plan.request.home)
  val resolvedSource = skill.sourceDir.toAbsolutePath().normalize()
  val resolvedIntentSource = intent.sourceDir.toAbsolutePath().normalize()
  require(resolvedIntentSource == resolvedSource) {
    "Install plan staging source '${intent.sourceDir}' does not match skill source '${skill.sourceDir}'."
  }
  require(intent.stagingRoot.toAbsolutePath().normalize() == expectedRoot) {
    "Install plan staging root '${intent.stagingRoot}' does not match expected installed-skills root '$expectedRoot'."
  }
  require(intent.contentHash.matches(plannedContentHashRegex)) {
    "Install plan staging hash '${intent.contentHash}' for '${skill.name}' is not a valid content hash."
  }
  val expectedStagingDir = installedSkillStagingDir(plan.request.home, resolvedSource, intent.contentHash)
  require(intent.stagingDir.toAbsolutePath().normalize() == expectedStagingDir) {
    "Install plan staging dir '${intent.stagingDir}' does not match expected dir '$expectedStagingDir'."
  }
  return materializeValidatedPlannedStaging(
    PlannedStagingMaterialization(
      plan = plan,
      skill = skill,
      intent = intent,
      platformManifests = platformManifests,
      resolvedSource = resolvedSource,
      expectedStagingDir = expectedStagingDir,
    ),
  )
}

private fun materializeValidatedPlannedStaging(inputs: PlannedStagingMaterialization): RenderedSkill {
  val plan = inputs.plan
  val skill = inputs.skill
  val intent = inputs.intent
  val pointers = applicablePointers(plan.request.repoRoot, inputs.resolvedSource, inputs.platformManifests)
  val supportPointers = generatedSupportPointersFor(
    repoRoot = plan.request.repoRoot,
    sourceSkillDir = inputs.resolvedSource,
    skillName = skill.name,
    skillsRoot = plan.request.targetPaths.skillsRoot,
    selectedPlatformManifests = selectedPlatformManifests(plan, inputs.platformManifests),
  )
  val selectedPackSkills = selectedInternalPackSkills(plan)
  val internalChildren = internalSidecarTargetsFor(plan, skill, selectedPackSkills)
  val sidecarNames = internalChildren.map { child -> "${child.skillName}.md" }.toSet()
  val authored = authoredFilesFor(inputs.resolvedSource, pointers, supportPointers, sidecarNames)
  val currentHash = computeInstallContentHash(
    sourceSkillDir = inputs.resolvedSource,
    authored = authored,
    applicablePointers = pointers,
    generatedSupportPointers = supportPointers,
    internalChildren = internalChildren,
  )
  require(currentHash == intent.contentHash) {
    "Planned staging for '${skill.name}' expected hash '${intent.contentHash}' but current source resolves " +
      "to '$currentHash'. Re-run planInstall before applyInstall."
  }
  if (isReusableInstallStaging(inputs.expectedStagingDir, intent.contentHash, sidecarNames)) {
    return reuseInstallStaging(
      sourceSkillDir = inputs.resolvedSource,
      finalStagingDir = inputs.expectedStagingDir,
      contentHash = intent.contentHash,
      applicablePointers = pointers,
      generatedSupportPointers = supportPointers,
      internalSidecarNames = sidecarNames,
    )
  }
  val staged = stageInstalledSkill(
    repoRoot = plan.request.repoRoot,
    sourceSkillDir = inputs.resolvedSource,
    home = plan.request.home,
    manifests = inputs.platformManifests,
    skillsRoot = plan.request.targetPaths.skillsRoot,
    selectedPackSkills = selectedPackSkills,
    selectedPlatformSlugs = selectedPlatformSlugs(plan, inputs.platformManifests),
  )
  val stagedDir = staged.stagingDir.toAbsolutePath().normalize()
  require(staged.contentHash == intent.contentHash && stagedDir == inputs.expectedStagingDir) {
    "Staged '${skill.name}' at '${staged.stagingDir}' with hash '${staged.contentHash}', but plan expected " +
      "'${inputs.expectedStagingDir}' with hash '${intent.contentHash}'."
  }
  return staged
}

private fun selectedPlatformManifests(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
): List<PlatformManifest> {
  val selected = selectedPlatformSlugs(plan, platformManifests)
  return platformManifests.filter { manifest -> manifest.slug in selected }
}

private fun selectedPlatformSlugs(plan: InstallPlan, platformManifests: List<PlatformManifest>): Set<String> =
  plan.skills
    .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK }
    .mapNotNull { skill ->
      platformManifests.firstOrNull { manifest -> skill.sourceDir.startsWith(manifest.packRoot) }?.slug
    }
    .toSet()

private fun selectedInternalPackSkills(plan: InstallPlan): List<InstallPlanSkill> = plan.skills.filter { skill ->
  skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null
}

private fun internalSidecarTargetsFor(
  plan: InstallPlan,
  skill: InstallPlanSkill,
  selectedPackSkills: List<InstallPlanSkill>,
) = discoverInternalSidecarTargets(
  repoRoot = plan.request.repoRoot,
  parentSkillName = skill.name,
  skillsRoot = plan.request.targetPaths.skillsRoot,
  selectedPackSkills = selectedPackSkills,
)

private data class PlannedStagingMaterialization(
  val plan: InstallPlan,
  val skill: InstallPlanSkill,
  val intent: InstallStagingPathIntent,
  val platformManifests: List<PlatformManifest>,
  val resolvedSource: Path,
  val expectedStagingDir: Path,
)
