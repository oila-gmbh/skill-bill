package skillbill.ports.persistence.model

import java.time.Instant

enum class WorkItemKind(val wireValue: String) {
  FEATURE_TASK_PROSE("feature-task-prose"),
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

data class WorkItem(
  val issueKey: String?,
  val workflowKind: WorkItemKind,
  val workflowId: String,
  val startedAt: Instant,
  val currentState: String,
  val stateEnteredAt: Instant,
  val stateEnteredAtEstimated: Boolean,
)
