package skillbill.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.install.model.InstallAgent

internal data class PersistedAgentAddonSelectionVerifyRequest(
  val selection: AgentAddonSelection,
  val consumer: AgentAddonConsumer,
  val receivingAgentIds: List<String>,
  val parseAgent: (String) -> InstallAgent,
  val validateCompatibility: (
    slug: String,
    consumers: List<AgentAddonConsumer>,
    agents: List<InstallAgent>,
    consumer: AgentAddonConsumer,
    receivingAgents: List<InstallAgent>,
  ) -> Unit,
  val stringList: (Map<*, *>, String) -> List<String>,
)
