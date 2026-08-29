package skillbill.cli.install

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode

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

  private fun SharedInstallSelection.toReplayText(): String = buildString {
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
