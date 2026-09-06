package skillbill.ports.idestatus

import WorkflowId
import skillbill.idestatus.model.AgentActivityStamp

object EmptyAgentActivityStampRepository : AgentActivityStampRepository {
  override fun record(workflowId: WorkflowId, stamp: AgentActivityStamp) {
  }

  override fun read(workflowId: WorkflowId): AgentActivityStamp? {
    return null
  }
}
