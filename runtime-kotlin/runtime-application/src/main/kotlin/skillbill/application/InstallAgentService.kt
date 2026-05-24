package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.InstallAgentGateway
import java.nio.file.Path

@Inject
class InstallAgentService(
  private val gateway: InstallAgentGateway,
) {
  fun agentPath(agent: String, home: Path? = null): Path = gateway.agentPath(agent, home)

  fun detectAgentTargets(home: Path? = null) = gateway.detectAgentTargets(home)

  fun codexAgentsPath(home: Path? = null): Path = gateway.codexAgentsPath(home)

  fun claudeAgentsPath(home: Path? = null): Path = gateway.claudeAgentsPath(home)

  fun opencodeAgentsPath(home: Path? = null): Path = gateway.opencodeAgentsPath(home)

  fun junieAgentsPath(home: Path? = null): Path = gateway.junieAgentsPath(home)

  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
  ) = gateway.cleanupAgentTarget(targetDir, skillNames, legacyNames, managedInstallMarker)
}
