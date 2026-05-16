package skillbill.install.model

import java.nio.file.Path

data class AgentTarget(
  val name: String,
  val path: Path,
)

enum class InstallAgent(
  val id: String,
) {
  COPILOT("copilot"),
  CLAUDE("claude"),
  CODEX("codex"),
  OPENCODE("opencode"),
  JUNIE("junie"),
  ;

  companion object {
    val supportedIds: List<String> = entries.map(InstallAgent::id)

    fun fromId(id: String): InstallAgent = entries.firstOrNull { agent -> agent.id == id }
      ?: throw IllegalArgumentException("Unknown agent '$id'. Supported agents: ${supportedIds.joinToString(", ")}.")
  }
}

enum class InstallAgentSelectionMode {
  DETECTED,
  MANUAL,
}

enum class InstallAgentTargetSource {
  DETECTED,
  MANUAL,
}

data class InstallAgentSelection(
  val mode: InstallAgentSelectionMode,
  val manualAgents: Set<InstallAgent> = emptySet(),
  val detectedTargets: List<InstallAgentTarget> = emptyList(),
)

data class InstallAgentTarget(
  val agent: InstallAgent,
  val path: Path,
  val source: InstallAgentTargetSource,
)

enum class PlatformPackSelectionMode {
  NONE,
  SELECTED,
  ALL,
}

data class PlatformPackSelection(
  val mode: PlatformPackSelectionMode,
  val selectedSlugs: Set<String> = emptySet(),
)

enum class InstallTelemetryLevel(
  val id: String,
) {
  ANONYMOUS("anonymous"),
  FULL("full"),
  OFF("off"),
}

data class RuntimeDistributionInputs(
  val runtimeInstallRoot: Path,
  val runtimeCliBuildDir: Path? = null,
  val runtimeMcpBuildDir: Path? = null,
  val runtimeCliInstallDir: Path? = null,
  val runtimeMcpInstallDir: Path? = null,
  val runtimeLauncherBinDir: Path? = null,
)

data class McpRegistrationChoice(
  val register: Boolean,
  val runtimeMcpBin: Path? = null,
)

data class InstallationTargetPaths(
  val skillsRoot: Path,
  val platformPacksRoot: Path,
  val agentTargets: List<InstallAgentTarget> = emptyList(),
)

enum class WindowsSymlinkPreflightState {
  NOT_WINDOWS,
  AVAILABLE,
  REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
  DECISION_REQUIRED,
}

enum class WindowsSymlinkDecision {
  NOT_REQUIRED,
  PROCEED_WITH_SYMLINKS,
  REQUIRE_USER_ACTION,
}

data class WindowsSymlinkPreflight(
  val state: WindowsSymlinkPreflightState,
  val decision: WindowsSymlinkDecision,
  val message: String = "",
)

data class InstallPlanRequest(
  val repoRoot: Path,
  val home: Path,
  val agentSelection: InstallAgentSelection,
  val platformPackSelection: PlatformPackSelection,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationChoice: McpRegistrationChoice,
  val runtimeDistributionInputs: RuntimeDistributionInputs,
  val targetPaths: InstallationTargetPaths,
  val windowsSymlinkPreflight: WindowsSymlinkPreflight,
)

enum class InstallPlanSkillKind {
  BASE,
  PLATFORM_PACK,
}

data class InstallPlanSkill(
  val name: String,
  val sourceDir: Path,
  val kind: InstallPlanSkillKind,
  val platformSlug: String? = null,
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

data class McpMutationResult(
  val agent: String,
  val configPath: Path,
  val changed: Boolean,
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
)
