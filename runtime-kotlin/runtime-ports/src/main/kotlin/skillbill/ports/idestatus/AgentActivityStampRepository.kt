package skillbill.ports.idestatus

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.idestatus.model.AgentActivityStamp

interface AgentActivityStampRepository {
  fun record(workflowId: String, stamp: AgentActivityStamp)

  fun read(workflowId: String): AgentActivityStamp?
}

object EmptyAgentActivityStampRepository : AgentActivityStampRepository {
  private const val NAME = "EmptyAgentActivityStampRepository"

  override fun record(workflowId: String, stamp: AgentActivityStamp) {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "record(workflowId=$workflowId)")
  }

  override fun read(workflowId: String): AgentActivityStamp? {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "read(workflowId=$workflowId)")
    return null
  }
}
