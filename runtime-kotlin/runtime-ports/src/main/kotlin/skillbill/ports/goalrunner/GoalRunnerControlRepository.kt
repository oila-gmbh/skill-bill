package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

interface GoalRunnerControlStateRepository {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState = GoalRunnerControlState()

  fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState = state

  fun clearControlState(parentWorkflowId: String) = Unit
}

interface GoalRunnerReviewPolicyRepository {
  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = null

  fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy = policy
}

interface GoalRunnerOutOfBandAcceptanceRepository {
  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance = acceptance

  fun clearOutOfBandAcceptances(parentWorkflowId: String) = Unit
}

interface GoalRunnerControlRepository :
  GoalRunnerControlStateRepository,
  GoalRunnerReviewPolicyRepository,
  GoalRunnerOutOfBandAcceptanceRepository

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository
