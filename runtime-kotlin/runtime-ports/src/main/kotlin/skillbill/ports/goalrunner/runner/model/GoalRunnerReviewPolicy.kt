package skillbill.ports.goalrunner.runner.model

import skillbill.workflow.decomposition.model.IssueKey

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId

data class GoalRunnerReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
)

data class GoalRunnerChildWorkflowSetup(
  val subtaskId: SubtaskId,
  val workflowId: WorkflowId,
  val goalBranch: String,
  val normalizedIssueKey: IssueKey,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val reviewPolicy: GoalRunnerReviewPolicy,
  val planningHydration: GoalChildPlanningHydrationRequest? = null,
) {
  init {
    require(subtaskId.value > 0) { "subtaskId must be positive." }
    require(workflowId.value.isNotBlank()) { "workflowId must not be blank." }
    require(goalBranch.isNotBlank()) { "goalBranch must not be blank." }
    require(normalizedIssueKey.value.isNotBlank()) { "normalizedIssueKey must not be blank." }
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity must not be blank." }
    require(governedSpecPath.isNotBlank()) { "governedSpecPath must not be blank." }
  }
}

data class GoalChildPlanningHydrationRequest(
  val identity: GoalPlanningIdentity,
  val provenance: GoalPlanningContractProvenance,
  val descriptor: GovernedGoalSubtaskDescriptor,
)
