package skillbill.ports.agentaddon.model

import skillbill.install.model.ExternalAgentAddonSource
import java.nio.file.Path

data class ExternalAgentAddonSourceConfigRequest(
  val userHome: Path,
  val environment: Map<String, String> = emptyMap(),
)

data class ExternalAgentAddonSourceConfigResult(
  val sources: List<ExternalAgentAddonSource> = emptyList(),
)
