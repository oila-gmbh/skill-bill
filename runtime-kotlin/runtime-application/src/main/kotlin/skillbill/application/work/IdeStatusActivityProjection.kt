package skillbill.application.work

import skillbill.application.idestatus.model.IdeStatusSnapshot
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.ports.persistence.UnitOfWork
import java.time.Instant

internal fun IdeStatusSnapshot.withAgentActivity(unitOfWork: UnitOfWork, workflowId: String): IdeStatusSnapshot {
  val stamp = unitOfWork.agentActivityStamps.read(workflowId) ?: return this
  return copy(
    lastAgentActivityAt = stamp.recordedAt,
    lastAgentActivityLabel = stamp.label,
  )
}

internal fun agentActivityFields(unitOfWork: UnitOfWork, workflowId: String?): Pair<Instant?, AgentActivityLabel?> {
  val id = workflowId?.takeIf(String::isNotBlank) ?: return null to null
  val stamp = unitOfWork.agentActivityStamps.read(id) ?: return null to null
  return stamp.recordedAt to stamp.label
}
