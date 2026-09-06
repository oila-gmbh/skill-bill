package skillbill.application.goalrunner.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import java.nio.file.Path

data class GoalRunnerResetRequest(
  val issueKey: IssueKey,
  val hard: Boolean,
  val preservePlanning: Boolean = false,
  val subtaskId: SubtaskId? = null,
  val deleteChildWorkflow: Boolean = false,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(!preservePlanning || hard) { "preservePlanning requires a hard reset." }
    require((subtaskId != null) == deleteChildWorkflow) {
      "subtaskId and deleteChildWorkflow must be supplied together."
    }
    require(subtaskId == null || subtaskId.value > 0) { "subtaskId must be positive." }
    require(!deleteChildWorkflow || !hard) { "Scoped child deletion is incompatible with a hard reset." }
    require(!deleteChildWorkflow || !preservePlanning) {
      "Scoped child deletion preserves planning intrinsically and cannot use preservePlanning."
    }
  }
}

data class GoalRunnerResetResult(
  val issueKey: IssueKey,
  val mode: String,
  val parentWorkflowId: WorkflowId,
  val before: GoalRunnerResetSnapshot,
  val after: GoalRunnerResetSnapshot,
  val recovery: GoalRunnerChildRecoveryDiagnostic? = null,
)

data class GoalRunnerChildRecoveryDiagnostic(
  val subtaskId: SubtaskId,
  val workflowId: WorkflowId,
  val classification: String,
  val recoveryCommand: String?,
)

data class GoalRunnerResetSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

data class GoalRunnerResetSubtaskSnapshot(
  val id: Int,
  val status: String,
  val branch: String?,
  val workflowId: WorkflowId?,
  val commitSha: String?,
  val blockedReason: String?,
  val lastResumableStep: String?,
)
