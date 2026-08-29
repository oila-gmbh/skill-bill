package skillbill.scaffold.runtime

import skillbill.nativeagent.rendering.discoverRepoNativeAgentSourceEntries
import skillbill.nativeagent.validation.validateRepoNativeAgents
import skillbill.scaffold.pointer.validateGeneratedArtifactGuard
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.scaffold.validation.validateGovernedSkillDrift
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

data class RepoValidationReport(
  val issues: List<String>,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val structuredIssues: List<RepoValidationIssue> = issues.map(RepoValidationIssue::fromRawIssue),
) {
  val passed: Boolean = issues.isEmpty()

  fun toPayload(): Map<String, Any?> = mapOf(
    "status" to if (passed) "passed" else "failed",
    "skill_count" to skillCount,
    "governed_addon_count" to addonCount,
    "platform_pack_count" to platformPackCount,
    "native_agent_count" to nativeAgentCount,
    "issues" to issues,
  )
}

data class RepoValidationIssue(
  val severity: RepoValidationIssueSeverity,
  val message: String,
  val sourcePath: String?,
  val code: String? = null,
  val name: String? = null,
  val exceptionName: String? = null,
) {
  companion object {
    fun fromRawIssue(raw: String): RepoValidationIssue {
      val separator = raw.indexOf(": ")
      return if (separator > 0 && raw.substring(0, separator).isNotBlank() &&
        !raw.substring(0, separator).contains(' ')
      ) {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = raw.substring(0, separator),
          message = raw.substring(separator + 2),
        )
      } else {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = null,
          message = raw,
        )
      }
    }
  }
}

enum class RepoValidationIssueSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class ReleaseRefMetadata(
  val tag: String,
  val version: String,
  val major: Int,
  val minor: Int,
  val patch: Int,
  val prerelease: Boolean,
  val prereleaseIdentifier: String?,
  val buildMetadata: String?,
) {
  fun toPayload(): Map<String, Any?> = mapOf(
    "tag" to tag,
    "version" to version,
    "major" to major,
    "minor" to minor,
    "patch" to patch,
    "prerelease" to prerelease,
    "prerelease_identifier" to prereleaseIdentifier,
    "build_metadata" to buildMetadata,
  )
}

class ReleaseLicensePolicyError(message: String) : IllegalArgumentException(message)
object RepoValidationRuntime {
  const val PRE_1_LICENSE_IDENTIFIER = "LicenseRef-Skill-Bill-Use-1.0"
  const val PROSPECTIVE_EFFECTIVE_VERSION_MARKER = "Prospective Effective Version: v0.1.2"
  const val TRANSITIONAL_LICENSE_MARKER = "Skill Bill Use License 1.0"

  fun validateRepo(repoRoot: Path): RepoValidationReport {
    val root = repoRoot.toAbsolutePath().normalize()
    val issues = mutableListOf<String>()
    val skillFiles = discoverSkillFiles(root, issues)
    val platformSkillFiles = discoverPlatformPackSkillFiles(root, issues)
    val skillNames = (skillFiles.keys + platformSkillFiles.keys).toSortedSet()
    val addonFiles = discoverAllAddonFiles(root)
    val platformPacks = validatePlatformPacks(root, issues)
    val portableReviewSkills = discoverPortableReviewSkills(root)
    val nativeAgentSources = runCatching { discoverRepoNativeAgentSourceEntries(root) }.getOrDefault(emptyList())

    skillFiles.forEach { (skillName, skillFile) ->
      validateInstallableSkill(skillName, skillFile, root, issues, validateSourceSidecars = true, portableReviewSkills)
    }
    platformSkillFiles.forEach { (skillName, skillFile) ->
      validateInstallableSkill(skillName, skillFile, root, issues, validateSourceSidecars = false, portableReviewSkills)
    }
    validateInternalSidecarCollisions(skillFiles + platformSkillFiles, issues)
    validateInternalSkillClassification(skillFiles, platformSkillFiles, issues)
    validateInternalSidecarReferences(skillFiles + platformSkillFiles, issues)
    validateSkillSourceShape(skillFiles.values, root, issues)
    addonFiles.forEach { addonFile ->
      validateAddonFile(addonFile, root, issues)
    }

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
    issues += validateRepoNativeAgents(root).issues
    issues += validatePointerTargetParityIssues(root)
    issues += validateGovernedSkillDrift(root).issues
    issues += validateGeneratedArtifactGuard(root).issues
    val substanceReport = PlatformPackSubstanceAudit.audit(root)
    issues += substanceReport.auditErrors
    issues += substanceReport.violations.map { it.format() }
    validateNoOrchestrationPathsInSkillBodies(root, skillFiles, platformSkillFiles, issues)

    return RepoValidationReport(
      issues = issues.sorted(),
      skillCount = skillNames.size,
      addonCount = addonFiles.size,
      platformPackCount = platformPacks,
      nativeAgentCount = nativeAgentSources.size,
    )
  }

  internal fun extractH2(text: String, heading: String): String {
    val normalized = text.replace("\r\n", "\n")
    val startMarker = "## $heading"
    val start = normalized.indexOf(startMarker)
    if (start < 0) return ""
    val next = normalized.indexOf("\n## ", start + startMarker.length)
    return normalized.substring(start, if (next < 0) normalized.length else next).trimEnd()
  }

  fun parseReleaseRef(rawValue: String): ReleaseRefMetadata =
    RepoValidationRuntimeReleasePolicy.parseReleaseRef(rawValue)

  fun validateReleaseRef(repoRoot: Path, rawValue: String, forcePrerelease: Boolean = false): ReleaseRefMetadata =
    RepoValidationRuntimeReleasePolicy.validateReleaseRef(repoRoot, rawValue, forcePrerelease)

  fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) {
    Files.writeString(
      outputPath,
      buildString {
        appendLine("tag=${metadata.tag}")
        appendLine("version=${metadata.version}")
        appendLine("prerelease=${if (metadata.prerelease) "true" else "false"}")
      },
      CREATE,
      APPEND,
    )
  }
}
