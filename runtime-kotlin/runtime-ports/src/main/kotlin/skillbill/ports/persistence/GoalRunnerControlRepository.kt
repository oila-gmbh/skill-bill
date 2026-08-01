package skillbill.ports.persistence

import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy

/**
 * Durable parent-owned goal controls. These records are authoritative runtime state and must not
 * be copied into the compact manifest projection consumed by orchestration.
 */
interface GoalRunnerControlRepository {
  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = null

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy = policy

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance = acceptance

  fun clearOutOfBandAcceptances(parentWorkflowId: String) = Unit
}

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository
