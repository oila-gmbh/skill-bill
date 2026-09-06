package skillbill.application.work
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.engine.model.WorkflowId
import java.time.Instant

internal fun agentActivityFields(unitOfWork: UnitOfWork, workflowId: WorkflowId?): Pair<Instant?, AgentActivityLabel?> {
  val id = workflowId?.takeIf { it.value.isNotBlank() } ?: return null to null
  val stamp = unitOfWork.agentActivityStamps.read(id) ?: return null to null
  return stamp.recordedAt to stamp.label
}
