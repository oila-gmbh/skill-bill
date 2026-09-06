package skillbill.application.goalrunner.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey

data class GoalRunnerPauseResult(
  val issueKey: IssueKey,
  val parentWorkflowId: WorkflowId? = null,
  val status: String,
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(status.isNotBlank()) { "status is required." }
  }
}

enum class GoalRunnerStopStatus(val wireValue: String) {
  STOPPED("stopped"),
  ALREADY_STOPPED("already_stopped"),
  NO_LIVE_LEASE("no_live_lease"),
  IDENTITY_MISMATCH("identity_mismatch"),
  NOT_FOUND("not_found"),
}

data class GoalRunnerStopVerbResult(
  val issueKey: IssueKey,
  val status: GoalRunnerStopStatus,
  val parentWorkflowId: WorkflowId? = null,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val terminationAttempted: Boolean = false,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerResumeResult(
  val issueKey: IssueKey,
  val parentWorkflowId: WorkflowId? = null,
  val status: String,
  val clearedPauseReason: String? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(status.isNotBlank()) { "status is required." }
  }
}
