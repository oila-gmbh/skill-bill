package skillbill.install.model

import java.nio.file.Path

enum class InstallPlanSkillKind {
  BASE,
  PLATFORM_PACK,
}

data class InstallPlanSkill(
  val name: String,
  val sourceDir: Path,
  val kind: InstallPlanSkillKind,
  val platformSlug: String? = null,
  val internalFor: String? = null,
)

data class PlannedPlatformPack(
  val slug: String,
  val packRoot: Path,
  val selected: Boolean,
)

data class InstallStagingPathIntent(
  val skillName: String,
  val sourceDir: Path,
  val stagingRoot: Path,
  val stagingDir: Path,
  val contentHash: String,
)

data class InstallStagingIntent(
  val root: Path,
  val skillPaths: List<InstallStagingPathIntent>,
)

data class McpRegistrationIntent(
  val register: Boolean,
  val runtimeMcpBin: Path?,
  val agents: List<InstallAgent>,
)

data class InstallPlan(
  val request: InstallPlanRequest,
  val agents: List<InstallAgentTarget>,
  val discoveredPlatformPacks: List<PlannedPlatformPack>,
  val selectedPlatformSlugs: List<String>,
  val skills: List<InstallPlanSkill>,
  val staging: InstallStagingIntent,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationIntent: McpRegistrationIntent,
  val runtimeDistributionInputs: RuntimeDistributionInputs,
  val installationTargetPaths: InstallationTargetPaths,
  val windowsSymlinkPreflight: WindowsSymlinkPreflight,
)

data class InstallTransaction(
  val createdSymlinks: MutableList<Path> = mutableListOf(),
)

data class McpProfileOutcome(val configPath: Path, val changed: Boolean)

class ClaudeMcpProfileFailure(
  message: String,
  val succeeded: List<McpProfileOutcome>,
) : IllegalArgumentException(message)

data class McpMutationResult(
  val agent: String,
  val configPath: Path,
  val changed: Boolean,
  val profiles: List<McpProfileOutcome> = emptyList(),
)

/**
 * Materialized staging directory for an installed skill.
 *
 * SKILL-40 subtask 2 stages skill installs into a content-addressable cache outside the repo
 * (`~/.skill-bill/installed-skills/<slug>-<hash>/`) so the source tree stays read-only. This DTO
 * exposes the staging layout so callers (install primitives, tests) can assert what was rendered.
 */
data class RenderedSkill(
  val skillName: String,
  val sourceSkillDir: Path,
  val stagingDir: Path,
  val renderedSkillFile: Path,
  val renderedPointerFiles: List<Path>,
  val copiedAuthoredFiles: List<Path>,
  val contentHash: String,
  val renderedSidecarFiles: List<Path> = emptyList(),
)

enum class InstallApplyStatus {
  SUCCESS,
  WARNING,
  FAILURE,
}

enum class InstallApplyIssueKind {
  STAGING_FAILED,
  SKILL_LINK_FAILED,
  NATIVE_AGENT_LINK_FAILED,
  TELEMETRY_APPLY_FAILED,
  MCP_REGISTRATION_FAILED,
  WINDOWS_SYMLINK_PRECHECK_FAILED,
  WINDOWS_SYMLINK_WARNING,
  REPO_LOCAL_CONFIG_SCAFFOLD_FAILED,
}

data class InstallApplyIssue(
  val kind: InstallApplyIssueKind,
  val message: String,
  val skillName: String? = null,
  val agent: InstallAgent? = null,
  val path: Path? = null,
  val guidance: String? = null,
  val causeClass: String? = null,
)

enum class InstallSkillStagingStatus {
  STAGED,
  FAILED,
}

data class InstallSkillStagingOutcome(
  val status: InstallSkillStagingStatus,
  val sourceDir: Path,
  val stagingDir: Path? = null,
  val renderedSkillFile: Path? = null,
  val renderedPointerFiles: List<Path> = emptyList(),
  val copiedAuthoredFiles: List<Path> = emptyList(),
  val contentHash: String? = null,
  val issue: InstallApplyIssue? = null,
  val renderedSidecarFiles: List<Path> = emptyList(),
)

enum class InstallAgentLinkStatus {
  CREATED,
  SKIPPED,
  WARNING,
  FAILED,
}

enum class WindowsSymlinkFallbackState {
  NOT_REQUIRED,
  PROCEEDING,
  USER_ACTION_REQUIRED,
  LINK_FAILED,
}

data class WindowsSymlinkApplyOutcome(
  val preflight: WindowsSymlinkPreflight,
  val fallbackState: WindowsSymlinkFallbackState,
  val guidance: String = "",
)

data class InstallAgentSkillLinkOutcome(
  val agent: InstallAgent,
  val targetDir: Path,
  val linkPath: Path,
  val linkTarget: Path,
  val status: InstallAgentLinkStatus,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

data class ResolvedInstalledAgents(
  val agents: Set<InstallAgent>,
) {
  companion object {
    val EMPTY: ResolvedInstalledAgents = ResolvedInstalledAgents(emptySet())

    fun fromApplyResult(status: InstallApplyStatus, skills: List<InstallAppliedSkill>): ResolvedInstalledAgents {
      if (status == InstallApplyStatus.FAILURE) {
        return EMPTY
      }
      return fromSuccessfulApplyOutcomes(skills)
    }

    fun fromSuccessfulApplyOutcomes(skills: List<InstallAppliedSkill>): ResolvedInstalledAgents {
      val resolvedAgents =
        skills
          .flatMap(InstallAppliedSkill::links)
          .filter { link ->
            link.status == InstallAgentLinkStatus.CREATED || link.status == InstallAgentLinkStatus.SKIPPED
          }
          .mapTo(mutableSetOf(), InstallAgentSkillLinkOutcome::agent)
      return ResolvedInstalledAgents(resolvedAgents)
    }
  }
}

data class InstallAppliedSkill(
  val skillName: String,
  val kind: InstallPlanSkillKind,
  val platformSlug: String? = null,
  val sourceDir: Path,
  val staging: InstallSkillStagingOutcome,
  val links: List<InstallAgentSkillLinkOutcome> = emptyList(),
)

enum class NativeAgentProviderId(
  val id: String,
) {
  CLAUDE("claude"),
  CODEX("codex"),
  JUNIE("junie"),
  CURSOR("cursor"),
}

enum class NativeAgentApplyStatus {
  LINKED,
  SKIPPED,
  WARNING,
  FAILED,
}

data class NativeAgentApplyOutcome(
  val provider: NativeAgentProviderId,
  val agent: InstallAgent,
  val status: NativeAgentApplyStatus,
  val path: Path? = null,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

enum class InstallTelemetryApplyStatus {
  SUCCESS,
  SKIPPED,
  FAILED,
}

data class InstallTelemetryApplyOutcome(
  val level: InstallTelemetryLevel,
  val status: InstallTelemetryApplyStatus,
  val configPath: Path? = null,
  val clearedEvents: Int = 0,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

enum class McpRegistrationApplyStatus {
  SUCCESS,
  SKIPPED,
  FAILED,
}

data class McpRegistrationApplyOutcome(
  val agent: InstallAgent,
  val status: McpRegistrationApplyStatus,
  val configPath: Path? = null,
  val changed: Boolean = false,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
  val profiles: List<McpProfileOutcome> = emptyList(),
)

data class InstallApplyResult(
  val status: InstallApplyStatus,
  val skills: List<InstallAppliedSkill>,
  val nativeAgents: List<NativeAgentApplyOutcome>,
  val telemetryOutcome: InstallTelemetryApplyOutcome,
  val mcpRegistrationOutcomes: List<McpRegistrationApplyOutcome>,
  val warnings: List<InstallApplyIssue>,
  val failures: List<InstallApplyIssue>,
  val windowsSymlinkOutcome: WindowsSymlinkApplyOutcome,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationIntent: McpRegistrationIntent,
) {
  val resolvedInstalledAgents: ResolvedInstalledAgents
    get() = ResolvedInstalledAgents.fromApplyResult(status, skills)
}
