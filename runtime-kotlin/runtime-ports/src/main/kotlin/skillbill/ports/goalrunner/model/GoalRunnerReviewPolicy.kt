package skillbill.ports.goalrunner.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.workflow.model.CodeReviewExecutionMode

data class GoalRunnerReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String? = null,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
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
