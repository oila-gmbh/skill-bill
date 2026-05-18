package skillbill.desktop.core.data.service

import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.FirstRunAgentOption
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallDetail
import skillbill.desktop.core.domain.model.FirstRunInstallDetailSeverity
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunInstallPlanHandle
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformPackOption
import skillbill.desktop.core.domain.model.FirstRunSetupAgent
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.install.InstallOperations
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Inject
@SingleIn(UserScope::class)
class JvmDesktopFirstRunGateway : DesktopFirstRunGateway {
  internal var repoRootProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
  internal var homeProvider: () -> Path = { Path.of(System.getProperty("user.home")) }
  internal var runtimeAssetsProvider: () -> DesktopRuntimeAssets = { JvmRuntimeAssetLocator(repoRootProvider).locate() }
  internal var planInstall: (InstallPlanRequest) -> InstallPlan = InstallOperations::planInstall
  internal var applyInstall: (InstallPlan) -> InstallApplyResult = InstallOperations::applyInstall
  internal var detectedAgentTargets: (
    Path,
  ) -> List<skillbill.install.model.AgentTarget> = InstallOperations::detectAgentTargets

  override fun hasExistingInstall(): Boolean = runCatching {
    val stagingRoot = homeProvider()
      .toAbsolutePath()
      .normalize()
      .resolve(".skill-bill/installed-skills")
    if (!Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
      return@runCatching false
    }
    Files.list(stagingRoot).use { entries ->
      entries.anyMatch { entry: Path ->
        !entry.fileName.toString().startsWith(".") &&
          Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
          Files.isRegularFile(entry.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS) &&
          Files.isRegularFile(entry.resolve(".content-hash"), LinkOption.NOFOLLOW_LINKS)
      }
    }
  }.getOrDefault(false)

  override suspend fun discoverSetup(): FirstRunDiscoveryResult = try {
    val home = homeProvider().toAbsolutePath().normalize()
    val detected = detectedAgentTargets(home).associateBy { target -> target.name }
    val plan = planInstall(defaultRequest(home = home, selectedAgentIds = detected.keys))
    FirstRunDiscoveryResult.Success(
      FirstRunSetupDiscovery(
        agents = FirstRunSetupAgent.entries.map { agent ->
          val target = detected[agent.id]
          FirstRunAgentOption(
            agentId = agent.id,
            displayName = agent.displayName,
            detected = target != null,
            detectedPath = target?.path?.toPortableString(),
            selected = target != null,
          )
        },
        platformPacks = plan.discoveredPlatformPacks.map { pack ->
          FirstRunPlatformPackOption(
            slug = pack.slug,
            packRoot = pack.packRoot.toPortableString(),
            selected = pack.selected,
          )
        },
        selectedPlatformSlugs = plan.selectedPlatformSlugs.toSet(),
      ),
    )
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Exception) {
    FirstRunDiscoveryResult.Failed(
      message = error.message.orEmpty().ifBlank { "Setup discovery failed." },
      exceptionName = error::class.simpleName,
    )
  }

  override suspend fun planSetup(request: FirstRunSetupRequest): FirstRunPlanResult = try {
    val home = homeProvider().toAbsolutePath().normalize()
    val plan = planInstall(defaultRequest(home = home, request = request))
    FirstRunPlanResult.Planned(plan.toFirstRunPlan())
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Exception) {
    FirstRunPlanResult.Failed(
      message = error.message.orEmpty().ifBlank { "Install planning failed." },
      exceptionName = error::class.simpleName,
    )
  }

  override suspend fun applySetup(plan: FirstRunInstallPlan): FirstRunApplyResult = try {
    val runtimePlan = (plan.handle as? RuntimeInstallPlanHandle)?.plan
      ?: return FirstRunApplyResult.Failed(
        FirstRunInstallOutcome(
          status = FirstRunInstallStatus.FAILURE,
          title = "Install plan is no longer available.",
          details = listOf(
            FirstRunInstallDetail(
              label = "Install",
              message = "Start setup again so the desktop gateway can rebuild the shared install plan.",
              severity = FirstRunInstallDetailSeverity.ERROR,
            ),
          ),
        ),
      )
    val result = applyInstall(runtimePlan)
    val outcome = result.toFirstRunOutcome()
    when (outcome.status) {
      FirstRunInstallStatus.FAILURE -> FirstRunApplyResult.Failed(outcome)
      FirstRunInstallStatus.SUCCESS,
      FirstRunInstallStatus.WARNING,
      -> FirstRunApplyResult.Applied(outcome)
    }
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Exception) {
    FirstRunApplyResult.Failed(
      outcome = FirstRunInstallOutcome(
        status = FirstRunInstallStatus.FAILURE,
        title = "Install failed.",
        details = listOf(
          FirstRunInstallDetail(
            label = error::class.simpleName.orEmpty().ifBlank { "Exception" },
            message = error.message.orEmpty().ifBlank { "The shared installer failed unexpectedly." },
            severity = FirstRunInstallDetailSeverity.ERROR,
          ),
        ),
      ),
      exceptionName = error::class.simpleName,
    )
  }

  private fun defaultRequest(home: Path, selectedAgentIds: Set<String>): InstallPlanRequest = defaultRequest(
    home = home,
    request = FirstRunSetupRequest(
      selectedAgentIds = selectedAgentIds,
      selectedPlatformSlugs = emptySet(),
      telemetryLevel = FirstRunTelemetryLevel.default,
      registerMcp = true,
    ),
  )

  private fun defaultRequest(home: Path, request: FirstRunSetupRequest): InstallPlanRequest {
    val runtimeAssets = runtimeAssetsProvider()
    val repoRoot = runtimeAssets.repoRoot.toAbsolutePath().normalize()
    val selectedAgents = request.selectedAgentIds.mapTo(mutableSetOf(), InstallAgent::fromId)
    return InstallPlanRequest(
      repoRoot = repoRoot,
      home = home,
      agentSelection = InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = selectedAgents,
      ),
      platformPackSelection = PlatformPackSelection(
        mode = if (request.selectedPlatformSlugs.isEmpty()) {
          PlatformPackSelectionMode.NONE
        } else {
          PlatformPackSelectionMode.SELECTED
        },
        selectedSlugs = request.selectedPlatformSlugs,
      ),
      telemetryLevel = request.telemetryLevel.toInstallTelemetryLevel(),
      mcpRegistrationChoice = McpRegistrationChoice(
        register = true,
        runtimeMcpBin = runtimeAssets.runtimeMcpBin() ?: defaultRuntimeMcpBin(home),
      ),
      runtimeDistributionInputs = RuntimeDistributionInputs(
        runtimeInstallRoot = home.resolve(".skill-bill/runtime"),
        runtimeCliBuildDir = runtimeAssets.runtimeCliDir,
        runtimeMcpBuildDir = runtimeAssets.runtimeMcpDir,
        runtimeCliInstallDir = home.resolve(".skill-bill/runtime/runtime-cli"),
        runtimeMcpInstallDir = home.resolve(".skill-bill/runtime/runtime-mcp"),
      ),
      targetPaths = InstallationTargetPaths(
        skillsRoot = runtimeAssets.skillsRoot,
        platformPacksRoot = runtimeAssets.platformPacksRoot,
      ),
      windowsSymlinkPreflight = defaultWindowsSymlinkPreflight(),
      replaceExistingSkillBillLinks = true,
    )
  }
}

private data class RuntimeInstallPlanHandle(val plan: InstallPlan) : FirstRunInstallPlanHandle

private fun FirstRunTelemetryLevel.toInstallTelemetryLevel(): InstallTelemetryLevel = when (this) {
  FirstRunTelemetryLevel.ANONYMOUS -> InstallTelemetryLevel.ANONYMOUS
  FirstRunTelemetryLevel.FULL -> InstallTelemetryLevel.FULL
  FirstRunTelemetryLevel.OFF -> InstallTelemetryLevel.OFF
}

private fun InstallPlan.toFirstRunPlan(): FirstRunInstallPlan = FirstRunInstallPlan(
  handle = RuntimeInstallPlanHandle(this),
  selectedAgentIds = agents.mapTo(mutableSetOf()) { target -> target.agent.id },
  selectedPlatformSlugs = selectedPlatformSlugs.toSet(),
  platformPacks = discoveredPlatformPacks.map { pack ->
    FirstRunPlatformPackOption(
      slug = pack.slug,
      packRoot = pack.packRoot.toPortableString(),
      selected = pack.selected,
    )
  },
  baseSkillCount = skills.count { skill -> skill.kind == InstallPlanSkillKind.BASE },
  platformSkillCount = skills.count { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK },
  stagingRoot = staging.root.toPortableString(),
)

private fun InstallApplyResult.toFirstRunOutcome(): FirstRunInstallOutcome {
  val status = when (status) {
    InstallApplyStatus.SUCCESS -> FirstRunInstallStatus.SUCCESS
    InstallApplyStatus.WARNING -> FirstRunInstallStatus.WARNING
    InstallApplyStatus.FAILURE -> FirstRunInstallStatus.FAILURE
  }
  return FirstRunInstallOutcome(
    status = status,
    title = when (status) {
      FirstRunInstallStatus.SUCCESS -> "Setup completed."
      FirstRunInstallStatus.WARNING -> "Setup completed with warnings."
      FirstRunInstallStatus.FAILURE -> "Setup failed."
    },
    details = buildList {
      add(
        FirstRunInstallDetail(
          label = "Skills",
          message = "${skills.count()} skills staged and linked.",
          path = skills.firstOrNull()?.staging?.stagingDir?.parent?.toPortableString(),
        ),
      )
      add(
        FirstRunInstallDetail(
          label = "Telemetry",
          message = telemetryOutcome.message.ifBlank { "Telemetry level: ${telemetryLevel.id}." },
          severity = telemetryOutcome.issue?.let { FirstRunInstallDetailSeverity.WARNING }
            ?: FirstRunInstallDetailSeverity.INFO,
          path = telemetryOutcome.configPath?.toPortableString(),
        ),
      )
      add(
        FirstRunInstallDetail(
          label = "Windows symlinks",
          message = windowsSymlinkOutcome.preflight.message.ifBlank {
            windowsSymlinkOutcome.fallbackState.name.lowercase().replace('_', ' ')
          },
          severity = when (windowsSymlinkOutcome.fallbackState.name) {
            "USER_ACTION_REQUIRED",
            "LINK_FAILED",
            -> FirstRunInstallDetailSeverity.ERROR
            "PROCEEDING" -> FirstRunInstallDetailSeverity.WARNING
            else -> FirstRunInstallDetailSeverity.INFO
          },
          guidance = windowsSymlinkOutcome.guidance.ifBlank { null },
        ),
      )
      warnings.mapToIssueDetails(FirstRunInstallDetailSeverity.WARNING).forEach(::add)
      failures.mapToIssueDetails(FirstRunInstallDetailSeverity.ERROR).forEach(::add)
      nativeAgents.forEach { native ->
        add(
          FirstRunInstallDetail(
            label = "Native agent ${native.provider.id}",
            message = native.message.ifBlank { native.status.name.lowercase().replace('_', ' ') },
            severity = native.issue?.let { FirstRunInstallDetailSeverity.ERROR }
              ?: FirstRunInstallDetailSeverity.INFO,
            agentId = native.agent.id,
            path = native.path?.toPortableString(),
            guidance = native.issue?.guidance,
          ),
        )
      }
      mcpRegistrationOutcomes.forEach { mcp ->
        add(
          FirstRunInstallDetail(
            label = "MCP ${mcp.agent.id}",
            message = mcp.message.ifBlank { mcp.status.name.lowercase().replace('_', ' ') },
            severity = mcp.issue?.let { FirstRunInstallDetailSeverity.WARNING }
              ?: FirstRunInstallDetailSeverity.INFO,
            agentId = mcp.agent.id,
            path = mcp.configPath?.toPortableString(),
            guidance = mcp.issue?.guidance,
          ),
        )
      }
    },
  )
}

private fun List<InstallApplyIssue>.mapToIssueDetails(
  severity: FirstRunInstallDetailSeverity,
): List<FirstRunInstallDetail> = map { issue ->
  FirstRunInstallDetail(
    label = issue.kind.name.lowercase().replace('_', ' '),
    message = issue.message,
    severity = severity,
    agentId = issue.agent?.id,
    path = issue.path?.toPortableString(),
    guidance = issue.guidance,
  )
}

private fun defaultRuntimeMcpBin(home: Path): Path {
  val scriptName = if (isWindows()) "runtime-mcp.bat" else "runtime-mcp"
  return home.resolve(".skill-bill/runtime/runtime-mcp/bin/$scriptName")
}

private fun defaultWindowsSymlinkPreflight(): WindowsSymlinkPreflight = if (isWindows()) {
  WindowsSymlinkPreflight(
    state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
    decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
    message = "Windows may require Developer Mode or an elevated shell before creating skill symlinks.",
  )
} else {
  WindowsSymlinkPreflight(
    state = WindowsSymlinkPreflightState.NOT_WINDOWS,
    decision = WindowsSymlinkDecision.NOT_REQUIRED,
  )
}

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun Path.toPortableString(): String = toString().replace('\\', '/')
