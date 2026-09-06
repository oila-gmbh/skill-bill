package skillbill.application.work.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId
import java.time.Instant

enum class WorkListItemKind(val wireValue: String) {
  FEATURE_TASK_PROSE("feature-task-prose"),
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

data class WorkListItem(
  val issueKey: IssueKey?,
  val workflowKind: WorkListItemKind,
  val workflowId: WorkflowId,
  val startedAt: Instant,
  val currentState: String,
  val stateEnteredAt: Instant,
  val stateEnteredAtEstimated: Boolean,
)

data class WorkListResult(
  val dbPath: String,
  val work: List<WorkListItem>,
)
