package skillbill.application.model

import java.time.Instant

enum class WorkListItemKind(val wireValue: String) {
  FEATURE_TASK_PROSE("feature-task-prose"),
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

data class WorkListItem(
  val issueKey: String?,
  val workflowKind: WorkListItemKind,
  val workflowId: String,
  val startedAt: Instant,
  val currentState: String,
  val stateEnteredAt: Instant,
  val stateEnteredAtEstimated: Boolean,
)

data class WorkListResult(
  val dbPath: String,
  val work: List<WorkListItem>,
)
