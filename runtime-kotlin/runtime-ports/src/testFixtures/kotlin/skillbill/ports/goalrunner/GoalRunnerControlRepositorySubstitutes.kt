package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository {
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState {
    return GoalRunnerControlState()
  }

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState {
    return state
  }

  override fun clearControlState(parentWorkflowId: String) {
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? {
    return null
  }

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy {
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> {
    return emptyMap()
  }

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
  }
}

object UnavailableGoalRunnerControlRepository : GoalRunnerControlRepository {
  private fun refuse(): Nothing = error("Goal-runner control persistence is unavailable.")

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = refuse()

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState =
    refuse()

  override fun clearControlState(parentWorkflowId: String) = refuse()

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = refuse()

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy =
    refuse()

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = refuse()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance = refuse()

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) = refuse()
}
