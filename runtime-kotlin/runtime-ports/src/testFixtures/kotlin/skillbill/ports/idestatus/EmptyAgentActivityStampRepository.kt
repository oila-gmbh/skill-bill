package skillbill.ports.idestatus

import skillbill.idestatus.model.AgentActivityStamp

object EmptyAgentActivityStampRepository : AgentActivityStampRepository {
  override fun record(workflowId: String, stamp: AgentActivityStamp) {
  }

  override fun read(workflowId: String): AgentActivityStamp? {
    return null
  }
}
