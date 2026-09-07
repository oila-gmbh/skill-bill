package skillbill.application.continuation.model

data class GoalContinuationCandidate(
  val parentWorkflowId: String,
  val issueKey: String,
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val updatedAt: String?,
  val summary: String,
)
