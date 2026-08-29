package skillbill.ports.goalrunner.runner.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.CodeReviewExecutionMode

data class GoalRunnerReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
)

data class GoalRunnerChildWorkflowSetup(
  val subtaskId: Int,
  val workflowId: String,
  val goalBranch: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val reviewPolicy: GoalRunnerReviewPolicy,
  val planningHydration: GoalChildPlanningHydrationRequest? = null,
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

data class GoalChildPlanningHydrationRequest(
  val identity: GoalPlanningIdentity,
  val provenance: GoalPlanningContractProvenance,
  val descriptor: GovernedGoalSubtaskDescriptor,
)
