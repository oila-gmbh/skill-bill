package skillbill.ports.goalrunner.model

import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.CodeReviewExecutionMode

data class GoalRunnerReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String? = null,
) {
  init {
    parallelReviewAgent?.let { require(it.isNotBlank()) { "parallelReviewAgent must not be blank." } }
  }
}

data class GoalRunnerChildWorkflowSetup(
  val subtaskId: Int,
  val workflowId: String,
  val goalBranch: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val reviewPolicy: GoalRunnerReviewPolicy,
) {
  init {
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(workflowId.isNotBlank()) { "workflowId must not be blank." }
    require(goalBranch.isNotBlank()) { "goalBranch must not be blank." }
    require(normalizedIssueKey.isNotBlank()) { "normalizedIssueKey must not be blank." }
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity must not be blank." }
    require(governedSpecPath.isNotBlank()) { "governedSpecPath must not be blank." }
  }
}
