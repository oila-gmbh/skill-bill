package skillbill.cli.system

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.system.UninstallFileSystemService
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import java.nio.file.Path

internal fun cleanupAgentInstallTargets(
  plan: UninstallPlan,
  installAgentService: InstallAgentService,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  plan.agentTargets.forEach { target ->
    runCatching {
      installAgentService.cleanupAgentTarget(
        targetDir = target,
        skillNames = plan.skillNames,
        legacyNames = plan.legacyNames,
        managedInstallMarker = MANAGED_INSTALL_MARKER,
        home = plan.home,
      )
    }.onSuccess { cleanup ->
      removed += cleanup.removed.map(Path::toString)
      skipped += cleanup.skipped.map(Path::toString)
    }.onFailure { error ->
      recorder.recordFailure("agent cleanup failed for $target", error)
    }
  }
}

internal fun cleanupNativeAgentInstallLinks(
  plan: UninstallPlan,
  installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  uninstallFileSystem: UninstallFileSystemService,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!plan.nativeSourceRoots.any(uninstallFileSystem::exists)) {
    return
  }
  val request = NativeAgentLinkRequest(
    platformPacksRoot = plan.stateRoot.resolve("platform-packs"),
    skillsRoot = plan.stateRoot.resolve("skills"),
    home = plan.home,
  )
  NativeAgentLinkProvider.entries.forEach { provider ->
    runCatching {
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = provider,
          linkRequest = request,
        ),
      )
    }.onSuccess { result -> removed += result.unlinked.map(Path::toString) }
      .onFailure { error ->
        recorder.recordFailure("native agent cleanup failed for ${provider.name.lowercase()}", error)
      }
  }
}

internal fun cleanupMcpRegistrations(
  plan: UninstallPlan,
  installMcpRegistrationPort: InstallMcpRegistrationPort,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  plan.mcpAgents.forEach { agent ->
    runCatching {
      installMcpRegistrationPort.unregisterMcp(
        InstallMcpUnregistrationRequest(
          agent = agent,
          home = plan.home,
        ),
      ).mutation
    }.onSuccess { mutation ->
      if (mutation.changed) removed += mutation.configPath.toString()
    }.onFailure { error ->
      if (error is ClaudeMcpProfileFailure) {
        removed += error.succeeded.filter { it.changed }.map { it.configPath.toString() }
      }
      recorder.recordFailure("MCP cleanup failed for $agent", error)
    }
  }
}

internal const val MANAGED_INSTALL_MARKER = "Managed by skill-bill install.sh"
