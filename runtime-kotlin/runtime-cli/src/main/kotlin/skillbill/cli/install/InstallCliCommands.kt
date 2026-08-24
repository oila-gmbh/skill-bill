package skillbill.cli.install

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.McpRegistrationService
import skillbill.application.scaffold.NativeAgentInstallService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
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
class InstallReconcileCommand(
  private val state: CliRunState,
  private val installService: InstallService,
) : InstallRequestCommand(
  "reconcile",
  "Reconcile a reinstall: compare upstream/local/baseline per-skill hashes and emit a machine-readable plan.",
) {
  // --repo-root/--skills/--platform-packs (inherited) describe the LOCAL copied
  // source under ~/.skill-bill. The upstream/candidate clone source is supplied
  // explicitly so reconciliation runs against a staged candidate BEFORE any swap.
  private val upstreamRepoRoot by option(
    "--upstream-repo-root",
    help = "Upstream/candidate repo root containing skills/ and platform-packs/ to reconcile against the local copy.",
  ).required()
  private val upstreamSkillsRoot by option(
    "--upstream-skills",
    help = "Upstream/candidate skills root. Defaults to <upstream-repo-root>/skills.",
  )
  private val upstreamPlatformPacksRoot by option(
    "--upstream-platform-packs",
    help = "Upstream/candidate platform-packs root. Defaults to <upstream-repo-root>/platform-packs.",
  )

  private val apply by option(
    "--apply",
    help = "Apply the computed plan: install changed upstream skills into the live tree and refresh the baseline.",
  ).flag(default = false)
  override fun run() {
    val localRequest = toRequest(state)
    val resolvedUpstreamRepoRoot = Path.of(upstreamRepoRoot).toAbsolutePath().normalize()
    val resolvedUpstreamSkills = upstreamSkillsRoot?.let(Path::of) ?: resolvedUpstreamRepoRoot.resolve("skills")
    val resolvedUpstreamPacks =
      upstreamPlatformPacksRoot?.let(Path::of) ?: resolvedUpstreamRepoRoot.resolve("platform-packs")

    if (apply) {
      // Apply is a durable mutation; gate it behind the goal-continuation refusal.
      if (state.refuseInstallMutationDuringGoalContinuation("reconcile")) {
        return
      }
      val outcome = installService.applyReconcile(
        InstallReconcileApplyRequest(
          home = state.userHome,
          upstreamRepoRoot = resolvedUpstreamRepoRoot,
          upstreamSkillsRoot = resolvedUpstreamSkills,
          upstreamPlatformPacksRoot = resolvedUpstreamPacks,
          localRepoRoot = localRequest.repoRoot,
          localSkillsRoot = localRequest.targetPaths.skillsRoot,
          localPlatformPacksRoot = localRequest.targetPaths.platformPacksRoot,
        ),
      )
      completeReconcile(
        outcome.plan,
        refreshed = outcome.refreshed,
        applied = true,
        installedPaths = outcome.installedPaths,
        prunedPaths = outcome.prunedPaths,
      )
      return
    }

    val plan = installService.reconcile(
      InstallReconcileRequest(
        home = state.userHome,
        upstreamRepoRoot = resolvedUpstreamRepoRoot,
        upstreamSkillsRoot = resolvedUpstreamSkills,
        upstreamPlatformPacksRoot = resolvedUpstreamPacks,
        localRepoRoot = localRequest.repoRoot,
        localSkillsRoot = localRequest.targetPaths.skillsRoot,
        localPlatformPacksRoot = localRequest.targetPaths.platformPacksRoot,
      ),
    )
    completeReconcile(plan, refreshed = false, applied = false, installedPaths = emptyList())
  }

  // Emit the STABLE line-oriented machine report as stdout (install.sh consumes it
  // line-by-line, FAIL-CLOSED on a missing/unparseable summary), while keeping the
  // structured payload for JSON consumers.
  private fun completeReconcile(
    plan: ReconciliationPlan,
    refreshed: Boolean,
    applied: Boolean,
    installedPaths: List<String>,
    prunedPaths: List<String> = emptyList(),
  ) {
    state.completeText(
      reconcileMachineReport(
        plan,
        refreshed = refreshed,
        applied = applied,
        installedPaths = installedPaths,
        prunedPaths = prunedPaths,
      ),
      reconcilePayload(
        plan,
        refreshed = refreshed,
        applied = applied,
        installedPaths = installedPaths,
        prunedPaths = prunedPaths,
      ),
    )
  }
}

@Inject
class InstallApplyCommand(
  private val state: CliRunState,
  private val runtimeContext: EnvironmentContext,
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
    val reboundContext = RuntimeContext(
      dbPathOverride = state.dbOverride ?: runtimeContext.dbPathOverride,
      userHome = plan.request.home,
    )
    return RuntimeComponent::class.create(reboundContext).telemetryLevelMutator
  }
}

@Inject
class InstallReplayLastSelectionCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
  private val installService: InstallService,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
) : DocumentedCliCommand(
  "replay-last-selection",
  "Print the latest successful install choices as tab-separated replay fields.",
) {
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root used to validate saved selected platform slugs.",
  ).required()
  private val skillsRoot by option(
    "--skills",
    help = "Base skills root used by the install planning fact collector.",
  ).required()

  override fun run() {
    try {
      val selection = installSelectionPersistencePort
        .readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(state.userHome))
        .selection
      val availablePlatformSlugs = installService.discoverPlatformPackSlugs(replayDiscoveryRequest())
      val staleSlugs = selection.platformPackSelection.selectedSlugs - availablePlatformSlugs
      require(staleSlugs.isEmpty()) {
        "Saved install selection references unavailable platform pack slug(s): " +
          "${staleSlugs.sorted().joinToString(", ")}."
      }
      state.completeText(selection.toReplayText(), emptyMap())
    } catch (error: SkillBillRuntimeException) {
      state.completeText("${error.message.orEmpty()}\n", emptyMap(), exitCode = 1)
    } catch (error: IllegalArgumentException) {
      state.completeText("${error.message.orEmpty()}\n", emptyMap(), exitCode = 1)
    }
  }

  private fun replayDiscoveryRequest(): InstallPlanRequest = InstallPlanRequest(
    repoRoot = Path.of(platformPacksRoot).toAbsolutePath().normalize().parent ?: Path.of(".").toAbsolutePath(),
    home = state.userHome,
    agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
    platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = McpRegistrationChoice(register = false),
    runtimeDistributionInputs = RuntimeDistributionInputs(
      runtimeInstallRoot = state.userHome.resolve(".skill-bill/runtime"),
    ),
    targetPaths = InstallationTargetPaths(
      skillsRoot = Path.of(skillsRoot),
      platformPacksRoot = Path.of(platformPacksRoot),
    ),
    windowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
    environment = state.environment,
  )

  private fun skillbill.install.model.SharedInstallSelection.toReplayText(): String = buildString {
    selectedAgents.map(InstallAgent::id).sorted().forEach { agentId ->
      append("agent\t")
      append(agentId)
      append('\t')
      append(installAgentService.agentPath(agentId, state.userHome, state.environment))
      append('\n')
    }
    append("platform-mode\t")
    append(platformPackSelection.mode.name.lowercase())
    append('\n')
    platformPackSelection.selectedSlugs.sorted().forEach { slug ->
      append("platform\t")
      append(slug)
      append('\n')
    }
    append("telemetry\t")
    append(telemetryLevel.id)
    append('\n')
    append("mcp\t")
    append(if (mcpRegistrationChoice.register) "register" else "skip")
    append('\n')
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
      environment = state.environment,
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
      home = state.userHome,
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
class InstallClaudeRootsCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("claude-roots", "Print every resolved Claude config root, one per line.") {
  override fun run() {
    val roots = installAgentService.claudeRoots(state.userHome, state.environment)
    state.completeText(
      roots.joinToString("\n") { root -> root.toString() },
      mapOf("roots" to roots.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexRootsCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("codex-roots", "Print every resolved Codex config root, one per line.") {
  override fun run() {
    val roots = installAgentService.codexRoots(state.userHome, state.environment)
    state.completeText(
      roots.joinToString("\n") { root -> root.toString() },
      mapOf("roots" to roots.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("codex-agents-path", "Print the Codex native subagent TOML directory.") {
  override fun run() {
    state.completeText(installAgentService.codexAgentsPath(state.userHome, state.environment).toString(), emptyMap())
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
class InstallJunieAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("junie-agents-path", "Print the Junie native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.junieAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallCursorAgentsPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("cursor-agents-path", "Print the Cursor native subagent markdown directory.") {
  override fun run() {
    state.completeText(installAgentService.cursorAgentsPath(state.userHome).toString(), emptyMap())
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
class InstallLinkCursorAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-cursor-agents", "Render and link Cursor native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-cursor-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.CURSOR,
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
class InstallUnlinkCursorAgentsCommand(
  private val state: CliRunState,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-cursor-agents", "Remove Cursor native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unlink-cursor-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CURSOR,
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
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
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
    val result = try {
      mcpRegistrationService.unregisterMcp(agent, state.userHome)
    } catch (error: ClaudeMcpProfileFailure) {
      val removed = changedProfilePathsText(error.succeeded)
      if (removed.isNotEmpty()) {
        state.liveStdout("$removed\n")
      }
      throw error
    }
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
  }
}

private fun mcpProfilePathsText(result: McpMutationResult): String = if (result.profiles.isEmpty()) {
  result.configPath.toString()
} else {
  changedProfilePathsText(result.profiles)
}

private fun changedProfilePathsText(profiles: List<McpProfileOutcome>): String = profiles
  .filter { it.changed }
  .joinToString("\n") { it.configPath.toString() }

private fun mcpProfilesMap(agent: String, result: McpMutationResult): Map<String, Any?> = mapOf(
  "agent" to agent,
  "changed" to result.changed,
  "profiles" to result.profiles.map { profile ->
    mapOf("config_path" to profile.configPath.toString(), "changed" to profile.changed)
  },
)
