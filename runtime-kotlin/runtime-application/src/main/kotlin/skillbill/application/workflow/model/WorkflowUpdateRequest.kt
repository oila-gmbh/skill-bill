package skillbill.application.workflow.model
import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId

data class WorkflowUpdateRequest(
  val workflowId: WorkflowId,
  val workflowStatus: String,
  val currentStepId: String = "",
  @OpenBoundaryMap("Caller-supplied JSON patch for workflow step updates")
  val stepUpdates: List<Map<String, Any?>>? = null,
  @OpenBoundaryMap("Caller-supplied JSON patch for durable workflow artifacts")
  val artifactsPatch: Map<String, Any?>? = null,
  val sessionId: SessionId = SessionId(""),
)

enum class WorkflowFamilyKind {
  VERIFY,
  TASK_RUNTIME,
}
