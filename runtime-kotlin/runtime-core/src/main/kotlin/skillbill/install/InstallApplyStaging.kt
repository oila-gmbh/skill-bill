package skillbill.install

import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.RenderedSkill
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
  )
  val authored = authoredFilesFor(inputs.resolvedSource, pointers, supportPointers)
  val currentHash = computeInstallContentHash(inputs.resolvedSource, authored, pointers, supportPointers)
  require(currentHash == intent.contentHash) {
    "Planned staging for '${skill.name}' expected hash '${intent.contentHash}' but current source resolves " +
      "to '$currentHash'. Re-run planInstall before applyInstall."
  }
  if (isReusableInstallStaging(inputs.expectedStagingDir, intent.contentHash)) {
    return reuseInstallStaging(
      sourceSkillDir = inputs.resolvedSource,
      finalStagingDir = inputs.expectedStagingDir,
      contentHash = intent.contentHash,
      applicablePointers = pointers,
      generatedSupportPointers = supportPointers,
    )
  }
  val staged = stageInstalledSkill(
    repoRoot = plan.request.repoRoot,
    sourceSkillDir = inputs.resolvedSource,
    home = plan.request.home,
    manifests = inputs.platformManifests,
  )
  val stagedDir = staged.stagingDir.toAbsolutePath().normalize()
  require(staged.contentHash == intent.contentHash && stagedDir == inputs.expectedStagingDir) {
    "Staged '${skill.name}' at '${staged.stagingDir}' with hash '${staged.contentHash}', but plan expected " +
      "'${inputs.expectedStagingDir}' with hash '${intent.contentHash}'."
  }
  return staged
}

private data class PlannedStagingMaterialization(
  val plan: InstallPlan,
  val skill: InstallPlanSkill,
  val intent: InstallStagingPathIntent,
  val platformManifests: List<PlatformManifest>,
  val resolvedSource: Path,
  val expectedStagingDir: Path,
)
