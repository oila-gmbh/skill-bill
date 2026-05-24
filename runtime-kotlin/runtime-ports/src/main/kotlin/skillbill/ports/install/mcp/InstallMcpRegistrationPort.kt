package skillbill.ports.install.mcp

import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest

interface InstallMcpRegistrationPort {
  fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult

  fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult
}
