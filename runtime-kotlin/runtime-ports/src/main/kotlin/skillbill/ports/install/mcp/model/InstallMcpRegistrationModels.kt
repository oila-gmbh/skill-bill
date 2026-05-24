package skillbill.ports.install.mcp.model

import skillbill.install.model.McpMutationResult
import java.nio.file.Path

data class InstallMcpRegistrationRequest(
  val agent: String,
  val runtimeMcpBin: Path,
  val home: Path?,
)

data class InstallMcpUnregistrationRequest(
  val agent: String,
  val home: Path?,
)

data class InstallMcpRegistrationResult(
  val mutation: McpMutationResult,
)
