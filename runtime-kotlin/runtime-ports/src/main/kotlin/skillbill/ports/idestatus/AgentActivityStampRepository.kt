package skillbill.ports.idestatus

import skillbill.idestatus.model.AgentActivityStamp

interface AgentActivityStampRepository {
  fun record(workflowId: String, stamp: AgentActivityStamp)

  fun read(workflowId: String): AgentActivityStamp?
}

object EmptyAgentActivityStampRepository : AgentActivityStampRepository {
  override fun record(workflowId: String, stamp: AgentActivityStamp) = Unit

  override fun read(workflowId: String): AgentActivityStamp? = null
}
