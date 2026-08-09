package skillbill.ports.persistence.model

import java.time.Instant

enum class WorkItemKind(val wireValue: String) {
  FEATURE_TASK_PROSE("feature-task-prose"),
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

/**
 * SKILL-175: the prose engine (`FeatureImplementWorkflowDefinition`) is deleted, but
 * [WorkItemKind.FEATURE_TASK_PROSE] is retained as a legacy read-only wire value so historical
 * prose rows keep listing in the work list. This is the frozen literal set of the historical
 * prose `workflow_status` values that definition used to declare — a closed constant that exists
 * only to keep those history rows decodable, not a surviving workflow definition. Nothing
 * dispatches on it.
 */
val LEGACY_FEATURE_TASK_PROSE_WORKFLOW_STATUSES: Set<String> =
  setOf("pending", "running", "completed", "failed", "abandoned", "blocked", "paused")

data class WorkItem(
  val issueKey: String?,
  val workflowKind: WorkItemKind,
  val workflowId: String,
  val startedAt: Instant,
  val currentState: String,
  val stateEnteredAt: Instant,
  val stateEnteredAtEstimated: Boolean,
)
