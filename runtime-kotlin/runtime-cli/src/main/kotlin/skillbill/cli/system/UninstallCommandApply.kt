package skillbill.cli.system

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.McpRegistrationService
import skillbill.application.scaffold.NativeAgentInstallService
import skillbill.application.system.UninstallFileSystemService
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import java.nio.file.Path

internal fun cleanupAgentInstallTargets(
  plan: UninstallPlan,
  installAgentService: InstallAgentService,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  warnings: MutableList<String>,
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
      warnings += "agent cleanup failed for $target: ${error.message.orEmpty()}"
    }
  }
}

internal fun cleanupNativeAgentInstallLinks(
  plan: UninstallPlan,
  nativeAgentInstallService: NativeAgentInstallService,
  uninstallFileSystem: UninstallFileSystemService,
  removed: MutableList<String>,
  warnings: MutableList<String>,
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
    runCatching { nativeAgentInstallService.unlinkNativeAgents(provider, request) }
      .onSuccess { unlinked -> removed += unlinked.map(Path::toString) }
      .onFailure { error ->
        warnings += "native agent cleanup failed for ${provider.name.lowercase()}: ${error.message.orEmpty()}"
      }
  }
}

internal fun cleanupMcpRegistrations(
  plan: UninstallPlan,
  mcpRegistrationService: McpRegistrationService,
  removed: MutableList<String>,
  warnings: MutableList<String>,
) {
  plan.mcpAgents.forEach { agent ->
    runCatching { mcpRegistrationService.unregisterMcp(agent, plan.home) }
      .onSuccess { mutation ->
        if (mutation.changed) removed += mutation.configPath.toString()
      }
      .onFailure { error ->
        if (error is ClaudeMcpProfileFailure) {
          removed += error.succeeded.filter { it.changed }.map { it.configPath.toString() }
        }
        warnings += "MCP cleanup failed for $agent: ${error.message.orEmpty()}"
      }
  }
}

internal const val MANAGED_INSTALL_MARKER = "Managed by skill-bill install.sh"
