package skillbill.ports.install.agent.model

import skillbill.install.model.AgentTarget
import skillbill.ports.install.model.InstallCleanupResult
import java.nio.file.Path

data class InstallAgentPathRequest(
  val agent: String,
  val home: Path?,
)

data class InstallAgentPathResult(
  val path: Path,
)

data class DetectInstallAgentTargetsRequest(
  val home: Path?,
)

data class DetectInstallAgentTargetsResult(
  val targets: List<AgentTarget>,
)

data class InstallAgentDirectoryRequest(
  val agent: String,
  val home: Path?,
)

data class ClaudeConfigRootsRequest(
  val home: Path?,
  val environment: Map<String, String>,
)

data class ClaudeConfigRootsResult(
  val roots: List<Path>,
)

data class InstallAgentDirectoryResult(
  val path: Path,
)

data class InstallAgentTargetCleanupRequest(
  val targetDir: Path,
  val skillNames: List<String>,
  val legacyNames: List<String>,
  val managedInstallMarker: String,
  val home: Path? = null,
)

data class InstallAgentTargetCleanupResult(
  val cleanup: InstallCleanupResult,
)
