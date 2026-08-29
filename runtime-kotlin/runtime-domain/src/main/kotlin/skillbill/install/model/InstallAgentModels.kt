package skillbill.install.model

import java.nio.file.Path

data class AgentTarget(
  val name: String,
  val path: Path,
)

enum class InstallAgent(
  val id: String,
) {
  CLAUDE("claude"),
  CODEX("codex"),
  JUNIE("junie"),
  CURSOR("cursor"),
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

/**
 * The headless CLI a runtime launch execs for an agent, in preference order, plus how an operator
 * obtains it. Agent detection during install keys off the agent's home directory, which an IDE
 * creates without ever installing a headless CLI, so availability of the launch binary is a
 * separate fact that only this catalog answers.
 */
data class AgentLauncherCli(
  val executables: List<String>,
  val installHint: String,
) {
  init {
    require(executables.isNotEmpty()) { "An agent launcher must declare at least one executable." }
  }
}

// Single source of truth for launcher-binary availability. The CLI preflight and the launcher's
// spawn-boundary backstop both derive from it, so an agent whose CLI is absent is refused by name
// with an install hint instead of surfacing as an opaque spawn failure several layers up.
val AGENT_LAUNCHER_CLIS: Map<InstallAgent, AgentLauncherCli> = mapOf(
  InstallAgent.CLAUDE to AgentLauncherCli(
    executables = listOf("claude"),
    installHint = "install Claude Code (https://docs.claude.com/en/docs/claude-code/setup)",
  ),
  InstallAgent.CODEX to AgentLauncherCli(
    executables = listOf("codex"),
    installHint = "install the Codex CLI (npm install -g @openai/codex)",
  ),
  InstallAgent.JUNIE to AgentLauncherCli(
    executables = listOf("junie"),
    installHint = "install the Junie CLI from JetBrains",
  ),
  // Current Cursor installs symlink both names; older ones ship only cursor-agent.
  InstallAgent.CURSOR to AgentLauncherCli(
    executables = listOf("agent", "cursor-agent"),
    installHint = "install the Cursor Agent CLI (curl https://cursor.com/install -fsS | bash)",
  ),
)

/**
 * Returns an actionable refusal when [agentId] names an agent whose headless CLI cannot be found by
 * [onPath], or null when the agent is unknown, has no declared launcher, or is available. [onPath]
 * is supplied by the caller so this stays effect-free and testable.
 */
fun unavailableAgentLauncherReason(agentId: String?, onPath: (String) -> Boolean): String? {
  val normalized = agentId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
  val agent = InstallAgent.entries.firstOrNull { candidate -> candidate.id == normalized }
  val launcher = agent?.let(AGENT_LAUNCHER_CLIS::get) ?: return null
  if (launcher.executables.any(onPath)) return null
  return agentLauncherUnavailableMessage(agent, launcher.executables.first(), launcher.installHint)
}

fun agentLauncherUnavailableMessage(agent: InstallAgent, executable: String, installHint: String): String =
  "Agent '${agent.id}' cannot run in runtime mode here: its headless CLI '$executable' is not on PATH. " +
    "Having the ${agent.id} editor or its home directory installed is not enough — the headless CLI is a " +
    "separate install. Either $installHint, or relaunch with a different --agent."

val MODEL_DIRECTIVE_CAPABLE_AGENTS: Set<InstallAgent> = setOf(
  InstallAgent.CLAUDE,
  InstallAgent.CODEX,
  InstallAgent.CURSOR,
)

fun supportsModelDirective(agentId: String?): Boolean {
  if (agentId == null) return false
  val normalized = agentId.trim().lowercase()
  return MODEL_DIRECTIVE_CAPABLE_AGENTS.any { capable -> capable.id == normalized }
}

/**
 * SKILL-64 Subtask 3 (AC18): pure, effect-free mapping from an already-read
 * execution-context environment map to the [InstallAgent] that most likely
 * invoked `skill-bill goal`. The function never reads process state itself; the
 * CLI/adapter layer reads the process environment (or test fixtures) and passes
 * the resulting immutable map in, keeping detection deterministic and testable.
 *
 * Detection is conservative: it returns `null` when the invoking agent cannot
 * be determined, and callers refuse to launch rather than guessing an agent.
 * Agent-specific markers are checked in a stable order; if multiple markers are
 * present the first matching agent in [INVOKING_AGENT_CONTEXT_SIGNALS] order
 * wins.
 */
object InvokingAgentContextResolver {
  /**
   * Ordered context signals mapping environment-variable markers to agents.
   * Order is significant: earlier entries win when several markers are present.
   * Markers are matched only when the variable is present with a non-blank
   * value, mirroring how each agent populates its own execution context.
   *
   * Every marker here must be one an agent sets for the duration of its own
   * session. Config-location variables such as `CODEX_HOME` are deliberately
   * excluded: operators export them from their shell profile and the runtime
   * itself forwards them into isolated child launches, so a present value says
   * nothing about who is running.
   */
  val INVOKING_AGENT_CONTEXT_SIGNALS: List<InvokingAgentContextSignal> = listOf(
    InvokingAgentContextSignal(InstallAgent.CLAUDE, listOf("CLAUDECODE", "CLAUDE_CODE", "CLAUDE_CODE_ENTRYPOINT")),
    InvokingAgentContextSignal(InstallAgent.CODEX, listOf("CODEX_SANDBOX", "CODEX_SANDBOX_ENV")),
    InvokingAgentContextSignal(InstallAgent.CURSOR, listOf("CURSOR_AGENT", "CURSOR_INVOKED_AS")),
  )

  /**
   * Resolve the invoking agent from [environment]. Returns `null` when no
   * agent-specific marker is present.
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
  val environment: Map<String, String> = emptyMap(),
)
