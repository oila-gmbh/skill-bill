package skillbill.ports.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import java.nio.file.Path

interface AgentAddonSelectionPort {
  fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection

  fun verifyPersisted(
    selection: AgentAddonSelection,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection
}
