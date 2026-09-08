package skillbill.cli.install

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.model.CliRunInputs
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Path

abstract class InstallRequestCommand(
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root containing skills/ and platform-packs/. Defaults to the current working directory.",
  ).default(".")
  private val skillsRoot by option("--skills", help = "Base skills root. Defaults to <repo-root>/skills.")
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root. Defaults to <repo-root>/platform-packs.",
  )
  private val agentMode by option("--agent-mode", help = "Agent selection mode.")
    .choice("detected", "manual")
    .default("detected")
  private val agents by option(
    "--agent",
    help = "Manual agent to include. Repeat for ${InstallAgent.supportedIds.joinToString(", ")}.",
  ).multiple()
  private val agentTargets by option(
    "--agent-target",
    help = "Manual target override in agent=path form. Repeat to override multiple agents.",
  ).multiple()
  private val platformMode by option("--platform-mode", help = "Platform pack selection mode.")
    .choice("none", "selected", "all")
    .default("none")
  private val platforms by option("--platform", help = "Selected platform pack slug. Repeat for multiple packs.")
    .multiple()
  private val telemetry by option("--telemetry", help = "Telemetry level to configure during apply.")
    .choice("anonymous", "full", "off")
    .default("anonymous")
  private val mcp by option("--mcp", help = "Whether apply should register the runtime MCP server.")
    .choice("register", "skip")
    .default("register")
  private val runtimeInstallRoot by option(
    "--runtime-install-root",
    help = "Runtime install root. Defaults to <home>/.skill-bill/runtime.",
  )
  private val runtimeCliBuildDir by option(
    "--runtime-cli-build-dir",
    help = "Optional runtime-cli build directory.",
  )
  private val runtimeMcpBuildDir by option(
    "--runtime-mcp-build-dir",
    help = "Optional runtime-mcp build directory.",
  )
  private val runtimeCliInstallDir by option(
    "--runtime-cli-install-dir",
    help = "Optional runtime-cli install directory.",
  )
  private val runtimeMcpInstallDir by option(
    "--runtime-mcp-install-dir",
    help = "Optional runtime-mcp install directory.",
  )
  private val runtimeLauncherBinDir by option(
    "--runtime-launcher-bin-dir",
    help = "Optional runtime launcher bin directory.",
  )
  private val runtimeMcpBin by option(
    "--runtime-mcp-bin",
    help = "Packaged runtime-mcp bin script for MCP registration.",
  )
  private val windowsSymlinkState by option(
    "--windows-symlink-state",
    help = "Structured Windows symlink preflight state.",
  )
    .choice("not-windows", "available", "requires-elevation-or-developer-mode", "decision-required")
    .default("not-windows")
  private val windowsSymlinkDecision by option(
    "--windows-symlink-decision",
    help = "Structured Windows symlink decision.",
  )
    .choice("not-required", "proceed-with-symlinks", "require-user-action")
    .default("not-required")
  private val windowsSymlinkMessage by option("--windows-symlink-message", help = "Structured Windows symlink message.")
    .default("")
  private val replaceExistingSkillBillLinks by option(
    "--replace-existing-skill-bill-links",
    help = "Remove existing Skill Bill skill links for selected agents before applying this install.",
  ).flag(default = false)
  protected val format by formatOption()

  protected fun toRequest(inputs: CliRunInputs): InstallPlanRequest {
    val resolvedRepoRoot = Path.of(repoRoot).toAbsolutePath().normalize()
    val explicitTargets = parseAgentTargets(agentTargets)
    val manualAgents = agents.map(InstallAgent::fromId).toSet()
    return InstallPlanRequest(
      repoRoot = resolvedRepoRoot,
      home = inputs.userHome,
      agentSelection = InstallAgentSelection(
        mode = selectedAgentMode(manualAgents, explicitTargets),
        manualAgents = manualAgents,
      ),
      platformPackSelection = PlatformPackSelection(
        mode = selectedPlatformMode(),
        selectedSlugs = platforms.toSet(),
      ),
      telemetryLevel = telemetryLevel(),
      mcpRegistrationChoice = McpRegistrationChoice(
        register = mcp == "register",
        runtimeMcpBin = runtimeMcpBin?.let(Path::of),
      ),
      runtimeDistributionInputs = RuntimeDistributionInputs(
        runtimeInstallRoot = runtimeInstallRoot?.let(Path::of) ?: inputs.userHome.resolve(".skill-bill/runtime"),
        runtimeCliBuildDir = runtimeCliBuildDir?.let(Path::of),
        runtimeMcpBuildDir = runtimeMcpBuildDir?.let(Path::of),
        runtimeCliInstallDir = runtimeCliInstallDir?.let(Path::of),
        runtimeMcpInstallDir = runtimeMcpInstallDir?.let(Path::of),
        runtimeLauncherBinDir = runtimeLauncherBinDir?.let(Path::of),
      ),
      targetPaths = InstallationTargetPaths(
        skillsRoot = skillsRoot?.let(Path::of) ?: resolvedRepoRoot.resolve("skills"),
        platformPacksRoot = platformPacksRoot?.let(Path::of) ?: resolvedRepoRoot.resolve("platform-packs"),
        agentTargets = explicitTargets,
      ),
      windowsSymlinkPreflight = WindowsSymlinkPreflight(
        state = windowsSymlinkPreflightState(),
        decision = windowsSymlinkPreflightDecision(),
        message = windowsSymlinkMessage,
      ),
      replaceExistingSkillBillLinks = replaceExistingSkillBillLinks,
      environment = inputs.environment,
    )
  }

  private fun selectedAgentMode(
    manualAgents: Set<InstallAgent>,
    explicitTargets: List<InstallAgentTarget>,
  ): InstallAgentSelectionMode = if (
    agentMode == "manual" ||
    manualAgents.isNotEmpty() ||
    explicitTargets.isNotEmpty()
  ) {
    InstallAgentSelectionMode.MANUAL
  } else {
    InstallAgentSelectionMode.DETECTED
  }

  private fun selectedPlatformMode(): PlatformPackSelectionMode = when {
    platforms.isNotEmpty() -> PlatformPackSelectionMode.SELECTED
    platformMode == "selected" -> PlatformPackSelectionMode.SELECTED
    platformMode == "all" -> PlatformPackSelectionMode.ALL
    else -> PlatformPackSelectionMode.NONE
  }

  private fun telemetryLevel(): InstallTelemetryLevel = when (telemetry) {
    "full" -> InstallTelemetryLevel.FULL
    "off" -> InstallTelemetryLevel.OFF
    "anonymous" -> InstallTelemetryLevel.ANONYMOUS
    else -> InstallTelemetryLevel.ANONYMOUS // open CLI flag value: unrecognized defaults to anonymous
  }

  private fun windowsSymlinkPreflightState(): WindowsSymlinkPreflightState = when (windowsSymlinkState) {
    "available" -> WindowsSymlinkPreflightState.AVAILABLE
    "requires-elevation-or-developer-mode" -> WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE
    "decision-required" -> WindowsSymlinkPreflightState.DECISION_REQUIRED
    "not-windows" -> WindowsSymlinkPreflightState.NOT_WINDOWS
    else -> WindowsSymlinkPreflightState.NOT_WINDOWS // open CLI flag value: unrecognized defaults to NOT_WINDOWS
  }

  private fun windowsSymlinkPreflightDecision(): WindowsSymlinkDecision = when (windowsSymlinkDecision) {
    "proceed-with-symlinks" -> WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS
    "require-user-action" -> WindowsSymlinkDecision.REQUIRE_USER_ACTION
    "not-required" -> WindowsSymlinkDecision.NOT_REQUIRED
    else -> WindowsSymlinkDecision.NOT_REQUIRED // open CLI flag value: unrecognized defaults to NOT_REQUIRED
  }
}

private fun parseAgentTargets(rawTargets: List<String>): List<InstallAgentTarget> = rawTargets.map { rawTarget ->
  val parts = rawTarget.split("=", limit = 2)
  require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
    "--agent-target must use agent=path form."
  }
  InstallAgentTarget(
    agent = InstallAgent.fromId(parts[0]),
    path = Path.of(parts[1]),
    source = InstallAgentTargetSource.MANUAL,
  )
}
