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
  ZCODE("zcode"),
  ;

  companion object {
    val supportedIds: List<String> = entries.map(InstallAgent::id)

    fun fromId(id: String): InstallAgent = entries.firstOrNull { agent -> agent.id == id }
      ?: throw IllegalArgumentException("Unknown agent '$id'. Supported agents: ${supportedIds.joinToString(", ")}.")

    fun fromNormalizedId(id: String, label: String = "agent"): InstallAgent {
      val normalized = id.trim().lowercase()
      require(normalized.isNotBlank()) { "$label is required. Supported agents: ${supportedIds.joinToString(", ")}." }
      return fromId(normalized)
    }
  }
}

// Single source of truth for agents skill-bill refuses to run in runtime mode. Every layer derives
// from this set: the CLI preflights, the launcher backstop, and the headless adapter registry, so
// re-enabling an agent's runtime path is a one-line change here rather than scattered edits that drift.
val RUNTIME_REFUSED_AGENTS: Set<InstallAgent> = setOf(InstallAgent.OPENCODE, InstallAgent.ZCODE)

fun isRuntimeRefusedAgent(agentId: String?): Boolean {
  if (agentId == null) return false
  val normalized = agentId.trim().lowercase()
  return RUNTIME_REFUSED_AGENTS.any { refused -> refused.id == normalized }
}

val MODEL_DIRECTIVE_CAPABLE_AGENTS: Set<InstallAgent> = setOf(InstallAgent.CLAUDE, InstallAgent.CODEX)

fun supportsModelDirective(agentId: String?): Boolean {
  if (agentId == null) return false
  val normalized = agentId.trim().lowercase()
  return MODEL_DIRECTIVE_CAPABLE_AGENTS.any { capable -> capable.id == normalized }
}

// Shared, agent-neutral refusal reason. Documents the observed harness failure mode for every
// refused agent so the CLI preflight, the spawn-boundary backstop, and the governed skill gates
// all carry the same actionable prose. opencode is hard-killed at the 120s Bash ceiling; zcode's
// foreground runtime exceeds that ceiling, and a detached zcode child emits no harvestable output
// before the supervisor kills it as unresponsive. Both redirect to the supported prose path.
const val RUNTIME_REFUSED_AGENT_MESSAGE: String =
  "Runtime mode is not supported on opencode or zcode in this harness. opencode's foreground Bash tool " +
    "is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; " +
    "zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no " +
    "harvestable output before the supervisor kills it as unresponsive. Use prose instead — run " +
    "bill-feature-task-prose for a single feature task, or bill-feature-goal mode:prose for a decomposed goal."

/**
 * SKILL-64 Subtask 3 (AC18): pure, effect-free mapping from an already-read
 * execution-context environment map to the [InstallAgent] that most likely
 * invoked `skill-bill goal`. The function never reads process state itself; the
 * CLI/adapter layer reads the process environment (or test fixtures) and passes
 * the resulting immutable map in, keeping detection deterministic and testable.
 *
 * Detection is best-effort and conservative: it returns `null` when the
 * invoking agent cannot be determined so that callers can fall through to a
 * documented last-resort default rather than guessing. Agent-specific markers
 * are checked in a stable order; if multiple markers are present the first
 * matching agent in [INVOKING_AGENT_CONTEXT_SIGNALS] order wins.
 */
object InvokingAgentContextResolver {
  /**
   * Ordered context signals mapping environment-variable markers to agents.
   * Order is significant: earlier entries win when several markers are present.
   * Markers are matched only when the variable is present with a non-blank
   * value, mirroring how each agent populates its own execution context.
   */
  val INVOKING_AGENT_CONTEXT_SIGNALS: List<InvokingAgentContextSignal> = listOf(
    InvokingAgentContextSignal(InstallAgent.CLAUDE, listOf("CLAUDECODE", "CLAUDE_CODE", "CLAUDE_CODE_ENTRYPOINT")),
    InvokingAgentContextSignal(InstallAgent.CODEX, listOf("CODEX_SANDBOX", "CODEX_SANDBOX_ENV", "CODEX_HOME")),
    InvokingAgentContextSignal(InstallAgent.OPENCODE, listOf("OPENCODE", "OPENCODE_BIN_PATH", "OPENCODE_CONFIG")),
    InvokingAgentContextSignal(InstallAgent.ZCODE, listOf("ZCODE_APP_VERSION", "ZCODE_BASE_URL")),
  )

  /**
   * Resolve the invoking agent from [environment]. Returns `null` when no
   * agent-specific marker is present, signalling callers to use their
   * documented last-resort default.
   */
  fun detect(environment: Map<String, String>): InstallAgent? = INVOKING_AGENT_CONTEXT_SIGNALS
    .firstOrNull { signal -> signal.markerKeys.any { key -> environment[key]?.isNotBlank() == true } }
    ?.agent
}

data class InvokingAgentContextSignal(
  val agent: InstallAgent,
  val markerKeys: List<String>,
) {
  init {
    require(markerKeys.isNotEmpty()) { "Invoking-agent context signal requires at least one marker key." }
    require(markerKeys.all(String::isNotBlank)) { "Invoking-agent context marker keys must not be blank." }
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
  val replaceExistingSkillBillLinks: Boolean = false,
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
  OPENCODE("opencode"),
  JUNIE("junie"),
  ZCODE("zcode"),
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
