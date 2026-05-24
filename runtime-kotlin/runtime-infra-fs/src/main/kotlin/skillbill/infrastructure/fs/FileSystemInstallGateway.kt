package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.InstallCleanupOperations
import skillbill.install.InstallNativeAgentOperations
import skillbill.install.InstallOperations
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.launcher.McpRegistrationOperations
import skillbill.ports.install.InstallPlanGateway
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.model.NativeAgentSkippedLink
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path
import skillbill.install.NativeAgentLinkOverrides as FsNativeAgentLinkOverrides
import skillbill.install.NativeAgentLinkRequest as FsNativeAgentLinkRequest

@Inject
class FileSystemInstallGateway : InstallPlanGateway {
  override fun planInstall(request: InstallPlanRequest) = InstallOperations.planInstall(request)

  override fun applyInstall(plan: InstallPlan, telemetryLevelMutator: TelemetryLevelMutator?) =
    InstallOperations.applyInstall(plan, telemetryLevelMutator)

  override fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path?, home: Path?) =
    InstallOperations.linkSkill(source, targetDir, agent, repoRoot, home)
}

@Inject
class FileSystemInstallAgentGateway : skillbill.ports.install.InstallAgentGateway {
  override fun agentPath(agent: String, home: Path?) = InstallOperations.agentPath(agent, home)

  override fun detectAgentTargets(home: Path?) = InstallOperations.detectAgentTargets(home)

  override fun codexAgentsPath(home: Path?) = InstallOperations.codexAgentsPath(home)

  override fun claudeAgentsPath(home: Path?) = InstallOperations.claudeAgentsPath(home)

  override fun opencodeAgentsPath(home: Path?) = InstallOperations.opencodeAgentsPath(home)

  override fun junieAgentsPath(home: Path?) = InstallOperations.junieAgentsPath(home)

  override fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
  ): InstallCleanupResult {
    val (removed, skipped) = InstallCleanupOperations.cleanupAgentTarget(
      targetDir = targetDir,
      skillNames = skillNames,
      legacyNames = legacyNames,
      managedInstallMarker = managedInstallMarker,
    )
    return InstallCleanupResult(removed = removed, skipped = skipped)
  }
}

@Inject
class FileSystemNativeAgentInstallGateway : skillbill.ports.install.NativeAgentInstallGateway {
  override fun linkNativeAgents(
    provider: NativeAgentLinkProvider,
    request: NativeAgentLinkRequest,
  ): NativeAgentLinkOutcome {
    val outcome = when (provider) {
      NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.linkClaudeAgents(request.toFsRequest())
      NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.linkCodexAgents(request.toFsRequest())
      NativeAgentLinkProvider.OPENCODE -> InstallNativeAgentOperations.linkOpencodeAgents(request.toFsRequest())
      NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.linkJunieAgents(request.toFsRequest())
    }
    return NativeAgentLinkOutcome(
      linked = outcome.linked,
      skipped = outcome.skipped.map { skip -> NativeAgentSkippedLink(skip.path, skip.reason) },
    )
  }

  override fun unlinkNativeAgents(provider: NativeAgentLinkProvider, request: NativeAgentLinkRequest): List<Path> =
    when (provider) {
      NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.unlinkClaudeAgents(request.toFsRequest())
      NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.unlinkCodexAgents(request.toFsRequest())
      NativeAgentLinkProvider.OPENCODE -> InstallNativeAgentOperations.unlinkOpencodeAgents(request.toFsRequest())
      NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.unlinkJunieAgents(request.toFsRequest())
    }
}

@Inject
class FileSystemMcpRegistrationGateway : skillbill.ports.install.McpRegistrationGateway {
  override fun registerMcp(agent: String, runtimeMcpBin: Path, home: Path?) =
    McpRegistrationOperations.register(agent, runtimeMcpBin, home)

  override fun unregisterMcp(agent: String, home: Path?) = McpRegistrationOperations.unregister(agent, home)
}

private fun NativeAgentLinkRequest.toFsRequest(): FsNativeAgentLinkRequest = FsNativeAgentLinkRequest(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  home = home,
  selectedPlatforms = selectedPlatforms,
  overrides = FsNativeAgentLinkOverrides(
    installCacheRoot = overrides.installCacheRoot,
    sourceRoots = overrides.sourceRoots,
    legacyManagedRoot = overrides.legacyManagedRoot,
  ),
)
