package skillbill.ports.agentaddon

import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigResult

interface ExternalAgentAddonSourceConfigPort {
  fun readExternalAgentAddonSources(
    request: ExternalAgentAddonSourceConfigRequest,
  ): ExternalAgentAddonSourceConfigResult
}
