package skillbill.install

import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.RenderedSkill
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun applyInstallPlan(
  plan: InstallPlan,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
): InstallApplyResult {
  val warnings = mutableListOf<InstallApplyIssue>()
  val failures = mutableListOf<InstallApplyIssue>()
  val windowsOutcome = windowsSymlinkApplyOutcome(plan)
  collectWindowsSymlinkIssues(plan, windowsOutcome, warnings, failures)
  if (failures.isNotEmpty()) {
    return InstallApplyResult(
      status = InstallApplyStatus.FAILURE,
      skills = emptyList(),
      nativeAgents = emptyList(),
      telemetryOutcome = skippedTelemetryOutcome(plan, "Skipped because install preflight failed."),
      mcpRegistrationOutcomes = skippedMcpRegistrationOutcomes(plan, "Skipped because install preflight failed."),
      warnings = warnings,
      failures = failures,
      windowsSymlinkOutcome = windowsOutcome,
      telemetryLevel = plan.telemetryLevel,
      mcpRegistrationIntent = plan.mcpRegistrationIntent,
    )
  }
  val platformManifests = discoverPlatformManifests(plan.installationTargetPaths.platformPacksRoot)
  cleanupExistingSkillBillLinks(plan, platformManifests, failures)
  val appliedSkills = applyPlannedSkills(plan, platformManifests, failures)
  val orchestrationLinks = applyOrchestrationLinks(plan, warnings)
  val nativeAgents = if (failures.isEmpty()) {
    applyNativeAgents(
      plan = plan,
      platformManifests = platformManifests,
      failures = failures,
    )
  } else {
    emptyList()
  }
  val finalWindowsOutcome = windowsOutcome.withSymlinkFailureState(failures)
  val telemetryOutcome = if (failures.isEmpty()) {
    applyTelemetryIntent(plan, warnings, telemetryLevelMutator)
  } else {
    skippedTelemetryOutcome(plan, "Skipped because install apply failed.")
  }
  val mcpRegistrationOutcomes = if (failures.isEmpty()) {
    applyMcpRegistrationIntent(plan, warnings)
  } else {
    skippedMcpRegistrationOutcomes(plan, "Skipped because install apply failed.")
  }
  return InstallApplyResult(
    status = aggregateApplyStatus(warnings, failures),
    skills = appliedSkills,
    nativeAgents = nativeAgents,
    orchestrationLinks = orchestrationLinks,
    telemetryOutcome = telemetryOutcome,
    mcpRegistrationOutcomes = mcpRegistrationOutcomes,
    warnings = warnings,
    failures = failures,
    windowsSymlinkOutcome = finalWindowsOutcome,
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = plan.mcpRegistrationIntent,
  )
}

private fun collectWindowsSymlinkIssues(
  plan: InstallPlan,
  windowsOutcome: WindowsSymlinkApplyOutcome,
  warnings: MutableList<InstallApplyIssue>,
  failures: MutableList<InstallApplyIssue>,
) {
  when (windowsOutcome.fallbackState) {
    WindowsSymlinkFallbackState.USER_ACTION_REQUIRED -> failures.add(
      InstallApplyIssue(
        kind = InstallApplyIssueKind.WINDOWS_SYMLINK_PRECHECK_FAILED,
        message = plan.windowsSymlinkPreflight.message.ifBlank {
          "Windows requires Developer Mode or an elevated shell before creating symlinks."
        },
        guidance = windowsOutcome.guidance,
      ),
    )
    WindowsSymlinkFallbackState.PROCEEDING -> warnings.add(
      InstallApplyIssue(
        kind = InstallApplyIssueKind.WINDOWS_SYMLINK_WARNING,
        message = plan.windowsSymlinkPreflight.message,
        guidance = windowsOutcome.guidance.takeIf(String::isNotBlank),
      ),
    )
    WindowsSymlinkFallbackState.NOT_REQUIRED,
    WindowsSymlinkFallbackState.LINK_FAILED,
    -> Unit
  }
}

private fun applyPlannedSkills(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
): List<InstallAppliedSkill> = selectedPlannedSkills(plan).map { skill ->
  val staging = stagePlannedSkill(
    plan = plan,
    skill = skill,
    platformManifests = platformManifests,
    failures = failures,
  )
  val links = staging.stagingDir?.takeIf { staging.status == InstallSkillStagingStatus.STAGED }
    ?.let { stagingDir ->
      linkPlannedSkill(
        skill = skill,
        stagingDir = stagingDir,
        plan = plan,
        failures = failures,
      )
    }
    .orEmpty()
  InstallAppliedSkill(
    skillName = skill.name,
    kind = skill.kind,
    platformSlug = skill.platformSlug,
    sourceDir = skill.sourceDir,
    staging = staging,
    links = links,
  )
}

private fun selectedPlannedSkills(plan: InstallPlan): List<InstallPlanSkill> {
  val selectedPlatformSlugs = plan.selectedPlatformSlugs.toSet()
  return plan.skills.filter { skill ->
    skill.kind == InstallPlanSkillKind.BASE || skill.platformSlug in selectedPlatformSlugs
  }
}

private fun stagePlannedSkill(
  plan: InstallPlan,
  skill: InstallPlanSkill,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
): InstallSkillStagingOutcome = runCatching {
  val intent = plannedStagingIntent(plan, skill)
  val staging = validatedPlannedStaging(
    plan = plan,
    skill = skill,
    intent = intent,
    platformManifests = platformManifests,
  )
  staging.toStagingOutcome(skill.sourceDir)
}.getOrElse { error ->
  failedStagingOutcome(skill.sourceDir, skill.name, error).also { outcome ->
    outcome.issue?.let(failures::add)
  }
}

private fun RenderedSkill.toStagingOutcome(sourceDir: Path): InstallSkillStagingOutcome = InstallSkillStagingOutcome(
  status = InstallSkillStagingStatus.STAGED,
  sourceDir = sourceDir,
  stagingDir = stagingDir,
  renderedSkillFile = renderedSkillFile,
  renderedPointerFiles = renderedPointerFiles,
  copiedAuthoredFiles = copiedAuthoredFiles,
  contentHash = contentHash,
)

private fun failedStagingOutcome(sourceDir: Path, skillName: String, error: Throwable): InstallSkillStagingOutcome {
  val issue = InstallApplyIssue(
    kind = InstallApplyIssueKind.STAGING_FAILED,
    message = error.message.orEmpty(),
    skillName = skillName,
    path = sourceDir,
    causeClass = error::class.qualifiedName,
  )
  return InstallSkillStagingOutcome(
    status = InstallSkillStagingStatus.FAILED,
    sourceDir = sourceDir,
    issue = issue,
  )
}

private fun windowsSymlinkApplyOutcome(plan: InstallPlan): WindowsSymlinkApplyOutcome {
  val preflight = plan.windowsSymlinkPreflight
  val fallbackState = when {
    preflight.decision == WindowsSymlinkDecision.REQUIRE_USER_ACTION ->
      WindowsSymlinkFallbackState.USER_ACTION_REQUIRED
    preflight.state == WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE ->
      WindowsSymlinkFallbackState.PROCEEDING
    else -> WindowsSymlinkFallbackState.NOT_REQUIRED
  }
  return WindowsSymlinkApplyOutcome(
    preflight = preflight,
    fallbackState = fallbackState,
    guidance = windowsSymlinkGuidance(),
  )
}

private fun WindowsSymlinkApplyOutcome.withSymlinkFailureState(
  failures: List<InstallApplyIssue>,
): WindowsSymlinkApplyOutcome {
  if (fallbackState == WindowsSymlinkFallbackState.USER_ACTION_REQUIRED) {
    return this
  }
  val symlinkCause = InstallSymlinkException::class.qualifiedName
  val hasSymlinkFailure = failures.any { issue -> issue.causeClass == symlinkCause }
  return if (hasSymlinkFailure) {
    copy(fallbackState = WindowsSymlinkFallbackState.LINK_FAILED, guidance = windowsSymlinkGuidance())
  } else {
    this
  }
}

private fun aggregateApplyStatus(
  warnings: List<InstallApplyIssue>,
  failures: List<InstallApplyIssue>,
): InstallApplyStatus = when {
  failures.isNotEmpty() -> InstallApplyStatus.FAILURE
  warnings.isNotEmpty() -> InstallApplyStatus.WARNING
  else -> InstallApplyStatus.SUCCESS
}
