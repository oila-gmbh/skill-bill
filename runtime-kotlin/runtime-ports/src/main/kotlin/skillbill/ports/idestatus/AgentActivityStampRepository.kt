package skillbill.ports.idestatus

import skillbill.idestatus.model.AgentActivityStamp
import skillbill.workflow.engine.model.WorkflowId

interface AgentActivityStampRepository {
  fun record(workflowId: WorkflowId, stamp: AgentActivityStamp)

  fun read(workflowId: WorkflowId): AgentActivityStamp?
}
