package skillbill.application.goalrunner.model

import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import java.nio.file.Path

data class GoalRunnerOperatorDecisionRequest(
  val issueKey: String,
  val subtaskId: Int,
  val decision: GoalSubtaskOperatorDecision,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

sealed interface GoalRunnerOperatorDecisionResult {
  data class Recorded(
    val issueKey: String,
    val parentWorkflowId: String,
    val subtaskId: Int,
    val workflowId: String,
    val decision: String,
  ) : GoalRunnerOperatorDecisionResult

  data class Rejected(val issueKey: String, val reason: String) : GoalRunnerOperatorDecisionResult
}
