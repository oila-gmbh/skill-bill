package skillbill.ports.install.agent

import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsResult
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryResult
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentPathResult
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupResult

interface InstallAgentTargetPort {
  fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult

  fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult

  fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult

  fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult
}
