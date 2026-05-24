package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.McpRegistrationGateway
import java.nio.file.Path

@Inject
class McpRegistrationService(
  private val gateway: McpRegistrationGateway,
) {
  fun registerMcp(agent: String, runtimeMcpBin: Path, home: Path? = null) =
    gateway.registerMcp(agent, runtimeMcpBin, home)

  fun unregisterMcp(agent: String, home: Path? = null) = gateway.unregisterMcp(agent, home)
}
