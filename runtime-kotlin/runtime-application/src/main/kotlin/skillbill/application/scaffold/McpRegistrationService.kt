package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import java.nio.file.Path

@Inject
class McpRegistrationService(
  private val mcpRegistrationPort: InstallMcpRegistrationPort,
) {
  fun registerMcp(agent: String, runtimeMcpBin: Path, home: Path? = null) = mcpRegistrationPort.registerMcp(
    InstallMcpRegistrationRequest(agent = agent, runtimeMcpBin = runtimeMcpBin, home = home),
  ).mutation

  fun unregisterMcp(agent: String, home: Path? = null) =
    mcpRegistrationPort.unregisterMcp(InstallMcpUnregistrationRequest(agent = agent, home = home)).mutation
}
