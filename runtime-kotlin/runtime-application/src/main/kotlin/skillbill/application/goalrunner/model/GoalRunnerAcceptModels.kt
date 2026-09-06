package skillbill.application.goalrunner.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import java.nio.file.Path

data class GoalRunnerAcceptRequest(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val commitSha: String,
  val reason: String,
  val repoRoot: Path? = null,
  val restoreAfterHardReset: Boolean = false,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(subtaskId.value > 0) { "subtaskId must be positive." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

sealed interface GoalRunnerAcceptResult {
  data class Accepted(
    val issueKey: IssueKey,
    val parentWorkflowId: WorkflowId,
    val subtaskId: SubtaskId,
    val commitSha: String,
    val reason: String,
    val acceptedAt: String,
    val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerAcceptResult

  data class Rejected(val issueKey: IssueKey, val reason: String) : GoalRunnerAcceptResult
}

sealed interface GoalRunnerAcceptanceEvidence {
  data class Resolved(val commitSha: String) : GoalRunnerAcceptanceEvidence
  data class Rejected(val reason: String) : GoalRunnerAcceptanceEvidence
}
