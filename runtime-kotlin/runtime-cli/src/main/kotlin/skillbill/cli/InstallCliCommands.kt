package skillbill.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.InstallAgentService
import skillbill.application.InstallService
import skillbill.application.McpRegistrationService
import skillbill.application.NativeAgentInstallService
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
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
import skillbill.model.RuntimeContext
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path

private const val GOAL_CONTINUATION_ENV = "SKILL_BILL_GOAL_CONTINUATION"
private const val GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE = 64

internal fun CliRunState.refuseInstallMutationDuringGoalContinuation(commandName: String): Boolean {
  if (environment[GOAL_CONTINUATION_ENV] != "1") {
    return false
  }
  val message =
    "Refusing to run skill-bill install $commandName during skill-bill goal-continuation.\n" +
      "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  completeText(
    "$message\n",
    mapOf(
      "status" to "error",
      "error" to message,
      "exit_code" to GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
    ),
    exitCode = GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
  )
  return true
}

internal fun completeNativeAgentLinkOutcome(state: CliRunState, outcome: NativeAgentLinkOutcome) {
  val text = (
    outcome.linked.map { path -> "linked\t$path" } +
      outcome.skipped.map { entry -> "skipped\t${entry.path}\t${entry.reason}" }
    ).joinToString("\n")
  state.completeText(
    text,
    mapOf(
      "linked" to outcome.linked.map(Path::toString),
      "skipped" to outcome.skipped.map { skip ->
        mapOf("path" to skip.path.toString(), "reason" to skip.reason)
      },
    ),
  )
}

@Inject
class InstallPlanCommand(
  private val state: CliRunState,
  private val installService: InstallService,
) : InstallRequestCommand("plan", "Plan a governed Skill Bill install without mutating user files.") {
  override fun run() {
    val plan = installService.planInstall(toRequest(state))
    state.complete(installPlanPayload(plan, installService), format)
  }
}

@Inject
class InstallApplyCommand(
  private val state: CliRunState,
  private val runtimeContext: RuntimeContext,
  private val installService: InstallService,
) : InstallRequestCommand("apply", "Apply a governed Skill Bill install through the shared runtime contract.") {
  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("apply")) {
      return
    }
    val plan = installService.planInstall(toRequest(state))
    val result = installService.applyInstall(plan, telemetryLevelMutator(plan))
    state.complete(
      installApplyPayload(plan, result, installService),
      format,
      exitCode = if (result.failures.isEmpty()) 0 else 1,
    )
  }

  private fun telemetryLevelMutator(plan: InstallPlan): TelemetryLevelMutator {
    val reboundContext = runtimeContext.copy(
      dbPathOverride = state.dbOverride ?: runtimeContext.dbPathOverride,
      userHome = plan.request.home,
    )
    return RuntimeComponent::class.create(reboundContext).telemetryLevelMutator
  }
}

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
    help = "Manual agent to include. Repeat for copilot, claude, codex, opencode, or junie.",
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

  protected fun toRequest(state: CliRunState): InstallPlanRequest {
    val resolvedRepoRoot = Path.of(repoRoot).toAbsolutePath().normalize()
    val explicitTargets = parseAgentTargets(agentTargets)
    val manualAgents = agents.map(InstallAgent::fromId).toSet()
    return InstallPlanRequest(
      repoRoot = resolvedRepoRoot,
      home = state.userHome,
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
        runtimeInstallRoot = runtimeInstallRoot?.let(Path::of) ?: state.userHome.resolve(".skill-bill/runtime"),
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
    else -> InstallTelemetryLevel.ANONYMOUS
  }

  private fun windowsSymlinkPreflightState(): WindowsSymlinkPreflightState = when (windowsSymlinkState) {
    "available" -> WindowsSymlinkPreflightState.AVAILABLE
    "requires-elevation-or-developer-mode" -> WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE
    "decision-required" -> WindowsSymlinkPreflightState.DECISION_REQUIRED
    else -> WindowsSymlinkPreflightState.NOT_WINDOWS
  }

  private fun windowsSymlinkPreflightDecision(): WindowsSymlinkDecision = when (windowsSymlinkDecision) {
    "proceed-with-symlinks" -> WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS
    "require-user-action" -> WindowsSymlinkDecision.REQUIRE_USER_ACTION
    else -> WindowsSymlinkDecision.NOT_REQUIRED
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

@Inject
class InstallCleanupAgentTargetCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("cleanup-agent-target", "Remove Skill Bill symlinks and managed dirs from one agent path.") {
  private val targetDir by option("--target-dir", help = "Agent install directory.").required()
  private val skillNames by option("--skill-name", help = "Current skill name to remove.").multiple()
  private val legacyNames by option("--legacy-name", help = "Legacy skill name to remove.").multiple()
  private val marker by option("--marker", help = "Managed install marker file.").default(".skill-bill-install")

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("cleanup-agent-target")) {
      return
    }
    val cleanup = installAgentService.cleanupAgentTarget(
      targetDir = Path.of(targetDir),
      skillNames = skillNames,
      legacyNames = legacyNames,
      managedInstallMarker = marker,
    )
    state.completeText(
      (
        cleanup.removed.map { path -> "removed\t$path" } +
          cleanup.skipped.map { path -> "skipped\t$path" }
        ).joinToString("\n"),
      mapOf("removed" to cleanup.removed.map(Path::toString), "skipped" to cleanup.skipped.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("codex-agents-path", "Print the Codex native subagent TOML directory.") {
  override fun run() {
    state.completeText(installAgentService.codexAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallClaudeAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("claude-agents-path", "Print the Claude native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.claudeAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallOpencodeAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("opencode-agents-path", "Print the OpenCode native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.opencodeAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallJunieAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("junie-agents-path", "Print the Junie native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.junieAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallLinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-claude-agents", "Render and link Claude native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-claude-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(NativeAgentLinkProvider.CLAUDE, nativeAgentLinkRequest()),
    )
  }

  private fun nativeAgentLinkRequest(): NativeAgentLinkRequest = NativeAgentLinkRequest(
    platformPacksRoot = Path.of(platformPacks),
    skillsRoot = skills?.let(Path::of),
    home = state.userHome,
    selectedPlatforms = platforms.ifEmpty { null },
  )
}

@Inject
class InstallUnlinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-claude-agents", "Remove Claude native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unlink-claude-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CLAUDE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkCodexAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-codex-agents", "Render and link Codex native subagent TOMLs from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-codex-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.CODEX,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkCodexAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-codex-agents", "Remove Codex native subagent TOML symlinks from candidate dirs.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unlink-codex-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CODEX,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkOpencodeAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand(
  "link-opencode-agents",
  "Render and link OpenCode native subagent markdown from source agents.",
) {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-opencode-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.OPENCODE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkOpencodeAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-opencode-agents", "Remove OpenCode native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unlink-opencode-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.OPENCODE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkJunieAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-junie-agents", "Render and link Junie native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-junie-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.JUNIE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkJunieAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-junie-agents", "Remove Junie native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unlink-junie-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.JUNIE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = state.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallRegisterMcpCommand(
  private val state: CliRunState,
  private val mcpRegistrationService: McpRegistrationService,
) : DocumentedCliCommand("register-mcp", "Register Skill Bill's packaged Kotlin MCP server for one agent.") {
  private val agent by argument(help = "Agent name.")
  private val runtimeMcpBin by option("--runtime-mcp-bin", help = "Packaged runtime-mcp bin script.").required()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("register-mcp")) {
      return
    }
    val result = mcpRegistrationService.registerMcp(agent, Path.of(runtimeMcpBin), state.userHome)
    state.completeText(result.configPath.toString(), mapOf("agent" to agent, "changed" to result.changed))
  }
}

@Inject
class InstallUnregisterMcpCommand(
  private val state: CliRunState,
  private val mcpRegistrationService: McpRegistrationService,
) : DocumentedCliCommand("unregister-mcp", "Remove Skill Bill MCP registration for one agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unregister-mcp")) {
      return
    }
    val result = mcpRegistrationService.unregisterMcp(agent, state.userHome)
    state.completeText(result.configPath.toString(), mapOf("agent" to agent, "changed" to result.changed))
  }
}
