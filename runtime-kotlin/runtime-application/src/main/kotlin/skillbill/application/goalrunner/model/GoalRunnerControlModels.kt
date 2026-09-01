package skillbill.application.goalrunner.model

data class GoalRunnerPauseResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: String,
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
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
  val issueKey: String,
  val status: GoalRunnerStopStatus,
  val parentWorkflowId: String? = null,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val terminationAttempted: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerResumeResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: String,
  val clearedPauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(status.isNotBlank()) { "status is required." }
  }
}
