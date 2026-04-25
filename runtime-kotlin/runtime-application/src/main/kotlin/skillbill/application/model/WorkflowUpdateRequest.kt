package skillbill.application.model

data class WorkflowUpdateRequest(
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String = "",
  val stepUpdates: List<Map<String, Any?>>? = null,
  val artifactsPatch: Map<String, Any?>? = null,
  val sessionId: String = "",
)

enum class WorkflowFamilyKind {
  IMPLEMENT,
  VERIFY,
}
