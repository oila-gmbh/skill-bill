package skillbill.scaffold.runtime

import skillbill.nativeagent.composition.NativeAgentCompositionContext
import skillbill.nativeagent.rendering.discoverRepoNativeAgentSourceEntries
import skillbill.nativeagent.validation.validateRepoNativeAgents
import skillbill.scaffold.pointer.validateGeneratedArtifactGuard
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.scaffold.validation.validateGovernedSkillDrift
import java.nio.file.Path

internal data class RepoValidationCollected(
  val issues: MutableList<String>,
  val skillNames: Set<String>,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
)

internal fun collectRepoValidationIssues(
  root: Path,
  nativeAgentCompositionContext: NativeAgentCompositionContext,
  plannedNativeAgentWorkerIssues: (Path) -> List<String> = { _ -> emptyList() },
): RepoValidationCollected {
  val issues = mutableListOf<String>()
  val skillFiles = discoverSkillFiles(root, issues)
  val platformSkillFiles = discoverPlatformPackSkillFiles(root, issues)
  val skillNames = (skillFiles.keys + platformSkillFiles.keys).toSortedSet()
  val addonFiles = discoverAllAddonFiles(root)
  val platformPacks = validatePlatformPacks(root, issues)
  val portableReviewSkills = discoverPortableReviewSkills(root)
  val nativeAgentSources = runCatching { discoverRepoNativeAgentSourceEntries(root) }.getOrDefault(emptyList())
  validateInstallableSkills(skillFiles, root, issues, portableReviewSkills, validateSourceSidecars = true)
  validateInstallableSkills(platformSkillFiles, root, issues, portableReviewSkills, validateSourceSidecars = false)
  validateInternalSidecarCollisions(skillFiles + platformSkillFiles, issues)
  validateInternalSkillClassification(skillFiles, platformSkillFiles, issues)
  validateInternalSidecarReferences(skillFiles + platformSkillFiles, issues)
  validateSkillSourceShape(skillFiles.values, root, issues)
  addonFiles.forEach { addonFile -> validateAddonFile(addonFile, root, issues) }
  validateReadme(
    root.resolve("README.md"),
    skillFiles.keys.toSet(),
    internalSkillNames(skillFiles + platformSkillFiles),
    issues,
  )
  validateSkillReferences(root, skillNames, issues)
  validateSkillOverrides(root.resolve(".agents/skill-overrides.example.md"), skillNames, required = true, issues)
  validateSkillOverrides(root.resolve(".agents/skill-overrides.md"), skillNames, required = false, issues)
  validateSupportingTargets(root, skillFiles.keys + platformSkillFiles.keys, issues)
  validateFeatureAddonDeclarations(root, issues)
  validateAgentAddons(root, issues)
  validateWorkflowContracts(root, issues)
  validateOrchestrationPlaybooks(root, issues)
  validateNoInlineTelemetryContractDrift(root, issues)
  validateSpecialistContractParity(root, issues)
  validatePluginManifest(root.resolve(".claude-plugin/plugin.json"), issues)
  issues += validateRepoNativeAgents(root, nativeAgentCompositionContext).issues
  issues += plannedNativeAgentWorkerIssues(root)
  issues += validatePointerTargetParityIssues(root)
  issues += validateGovernedSkillDrift(root).issues
  issues += validateGeneratedArtifactGuard(root).issues
  val substanceReport = PlatformPackSubstanceAudit.audit(root)
  issues += substanceReport.auditErrors
  issues += substanceReport.violations.map { it.format() }
  validateNoOrchestrationPathsInSkillBodies(root, skillFiles, platformSkillFiles, issues)
  return RepoValidationCollected(
    issues = issues,
    skillNames = skillNames,
    addonCount = addonFiles.size,
    platformPackCount = platformPacks,
    nativeAgentCount = nativeAgentSources.size,
  )
}

private fun validateInstallableSkills(
  skillFiles: Map<String, Path>,
  root: Path,
  issues: MutableList<String>,
  portableReviewSkills: Set<String>,
  validateSourceSidecars: Boolean,
) {
  skillFiles.forEach { (skillName, skillFile) ->
    validateInstallableSkill(
      ValidateInstallableSkillArgs(
        skillName = skillName,
        contentFile = skillFile,
        root = root,
        issues = issues,
        validateSourceSidecars = validateSourceSidecars,
        portableReviewSkills = portableReviewSkills,
      ),
    )
  }
}
