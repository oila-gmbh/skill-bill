package skillbill.application.goalrunner.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import java.nio.file.Path

data class GoalRunnerOperatorDecisionRequest(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val decision: GoalSubtaskOperatorDecision,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(subtaskId.value > 0) { "subtaskId must be positive." }
  }
}

sealed interface GoalRunnerOperatorDecisionResult {
  data class Recorded(
    val issueKey: IssueKey,
    val parentWorkflowId: WorkflowId,
    val subtaskId: SubtaskId,
    val workflowId: WorkflowId,
    val decision: String,
  ) : GoalRunnerOperatorDecisionResult

  data class Rejected(val issueKey: IssueKey, val reason: String) : GoalRunnerOperatorDecisionResult
}
