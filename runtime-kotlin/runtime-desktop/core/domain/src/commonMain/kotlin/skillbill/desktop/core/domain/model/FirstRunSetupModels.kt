package skillbill.desktop.core.domain.model

enum class FirstRunSetupStep {
  AGENTS,
  PLATFORM_PACKS,
  PREFERENCES,
  APPLY,
  RESULT,
}

enum class FirstRunSetupAgent(
  val id: String,
  val displayName: String,
) {
  COPILOT("copilot", "Copilot"),
  CLAUDE("claude", "Claude"),
  CODEX("codex", "Codex"),
  OPENCODE("opencode", "OpenCode"),
  JUNIE("junie", "Junie"),
  ;

  companion object {
    val supportedIds: List<String> = entries.map(FirstRunSetupAgent::id)

    fun fromId(id: String): FirstRunSetupAgent? = entries.firstOrNull { agent -> agent.id == id }
  }
}

enum class FirstRunTelemetryLevel(
  val id: String,
  val displayName: String,
) {
  ANONYMOUS("anonymous", "Anonymous"),
  FULL("full", "Full"),
  OFF("off", "Off"),
  ;

  companion object {
    val default: FirstRunTelemetryLevel = ANONYMOUS

    fun fromId(id: String): FirstRunTelemetryLevel = entries.firstOrNull { level -> level.id == id } ?: default
  }
}

enum class FirstRunPlatformSelectionMode {
  NONE,
  SELECTED,
  ALL,
}

data class FirstRunDetectedAgent(
  val agentId: String,
  val path: String,
)

data class FirstRunAgentOption(
  val agentId: String,
  val displayName: String,
  val detected: Boolean,
  val detectedPath: String? = null,
  val selected: Boolean = detected,
)

data class FirstRunPlatformPackOption(
  val slug: String,
  val packRoot: String,
  val selected: Boolean = false,
)

data class FirstRunSetupDiscovery(
  val agents: List<FirstRunAgentOption>,
  val platformPacks: List<FirstRunPlatformPackOption>,
  val selectedPlatformSlugs: Set<String> = platformPacks.filter { pack -> pack.selected }
    .map { pack -> pack.slug }
    .toSet(),
)

data class FirstRunSetupRequest(
  val selectedAgentIds: Set<String>,
  val selectedPlatformSlugs: Set<String>,
  val telemetryLevel: FirstRunTelemetryLevel,
  val registerMcp: Boolean,
  val platformSelectionMode: FirstRunPlatformSelectionMode = if (selectedPlatformSlugs.isEmpty()) {
    FirstRunPlatformSelectionMode.NONE
  } else {
    FirstRunPlatformSelectionMode.SELECTED
  },
)

interface FirstRunInstallPlanHandle

data class FirstRunInstallPlan(
  val handle: FirstRunInstallPlanHandle,
  val selectedAgentIds: Set<String>,
  val selectedPlatformSlugs: Set<String>,
  val platformPacks: List<FirstRunPlatformPackOption>,
  val baseSkillCount: Int,
  val platformSkillCount: Int,
  val stagingRoot: String,
)

enum class FirstRunInstallStatus {
  SUCCESS,
  WARNING,
  FAILURE,
}

enum class FirstRunInstallDetailSeverity {
  INFO,
  WARNING,
  ERROR,
}

data class FirstRunInstallDetail(
  val label: String,
  val message: String,
  val severity: FirstRunInstallDetailSeverity = FirstRunInstallDetailSeverity.INFO,
  val agentId: String? = null,
  val path: String? = null,
  val guidance: String? = null,
)

data class FirstRunInstallOutcome(
  val status: FirstRunInstallStatus,
  val title: String,
  val details: List<FirstRunInstallDetail> = emptyList(),
)

sealed interface FirstRunDiscoveryResult {
  data class Success(val discovery: FirstRunSetupDiscovery) : FirstRunDiscoveryResult
  data class Failed(val message: String, val exceptionName: String? = null) : FirstRunDiscoveryResult
}

sealed interface FirstRunPlanResult {
  data class Planned(val plan: FirstRunInstallPlan) : FirstRunPlanResult
  data class Failed(val message: String, val exceptionName: String? = null) : FirstRunPlanResult
}

sealed interface FirstRunApplyResult {
  data class Applied(val outcome: FirstRunInstallOutcome) : FirstRunApplyResult
  data class Failed(val outcome: FirstRunInstallOutcome, val exceptionName: String? = null) : FirstRunApplyResult
}

data class FirstRunSetupState(
  val step: FirstRunSetupStep = FirstRunSetupStep.AGENTS,
  val agentOptions: List<FirstRunAgentOption> = FirstRunSetupAgent.entries.map { agent ->
    FirstRunAgentOption(agentId = agent.id, displayName = agent.displayName, detected = false)
  },
  val platformPacks: List<FirstRunPlatformPackOption> = emptyList(),
  val selectedAgentIds: Set<String> = agentOptions.filter { option -> option.selected }
    .map { option -> option.agentId }
    .toSet(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
  val platformSelectionMode: FirstRunPlatformSelectionMode = if (selectedPlatformSlugs.isEmpty()) {
    FirstRunPlatformSelectionMode.NONE
  } else {
    FirstRunPlatformSelectionMode.SELECTED
  },
  val telemetryLevel: FirstRunTelemetryLevel = FirstRunTelemetryLevel.default,
  val registerMcp: Boolean = true,
  val discoveryLoaded: Boolean = false,
  val busy: Boolean = false,
  val errorMessage: String? = null,
  val plan: FirstRunInstallPlan? = null,
  val outcome: FirstRunInstallOutcome? = null,
) {
  val canContinue: Boolean
    get() = when (step) {
      FirstRunSetupStep.AGENTS -> selectedAgentIds.isNotEmpty() && !busy
      FirstRunSetupStep.PLATFORM_PACKS,
      FirstRunSetupStep.PREFERENCES,
      -> !busy
      FirstRunSetupStep.APPLY -> selectedAgentIds.isNotEmpty() && !busy
      FirstRunSetupStep.RESULT -> outcome?.status != FirstRunInstallStatus.FAILURE
    }

  fun request(): FirstRunSetupRequest = FirstRunSetupRequest(
    selectedAgentIds = selectedAgentIds,
    selectedPlatformSlugs = selectedPlatformSlugs,
    telemetryLevel = telemetryLevel,
    registerMcp = registerMcp,
    platformSelectionMode = platformSelectionMode,
  )
}

data class PostPublishReinstallState(
  val selectedAgentIds: Set<String>,
  val selectedPlatformSlugs: Set<String>,
  val telemetryLevel: FirstRunTelemetryLevel,
  val registerMcp: Boolean,
  val platformSelectionMode: FirstRunPlatformSelectionMode = if (selectedPlatformSlugs.isEmpty()) {
    FirstRunPlatformSelectionMode.NONE
  } else {
    FirstRunPlatformSelectionMode.SELECTED
  },
  val busy: Boolean = false,
  val outcome: FirstRunInstallOutcome? = null,
) {
  val hasFinished: Boolean
    get() = outcome != null
}
