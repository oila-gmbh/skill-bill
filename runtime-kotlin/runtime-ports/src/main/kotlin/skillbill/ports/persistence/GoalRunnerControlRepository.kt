package skillbill.ports.persistence

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy

/**
 * Durable parent-owned goal controls. These records are authoritative runtime state and must not
 * be copied into the compact manifest projection consumed by orchestration.
 */
@Suppress("TooManyFunctions") // controls, policy, acceptances, and the fenced parent lease share one persistence row
interface GoalRunnerControlRepository {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState = GoalRunnerControlState()

  fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState = state

  fun clearControlState(parentWorkflowId: String) = Unit

  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = null

  fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy = policy

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance = acceptance

  fun clearOutOfBandAcceptances(parentWorkflowId: String) = Unit

  fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
    controlState(parentWorkflowId).executionLease

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String? = null,
  ): Boolean {
    val state = controlState(parentWorkflowId)
    if (state.executionLease?.ownerToken != expectedOwnerToken) return false
    persistControlState(parentWorkflowId, state.copy(executionLease = lease))
    return true
  }

  fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean {
    val state = controlState(parentWorkflowId)
    val current = state.executionLease ?: return false
    if (current.ownerToken != lease.ownerToken || current.generation != lease.generation) return false
    persistControlState(parentWorkflowId, state.copy(executionLease = lease))
    return true
  }

  fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean {
    val state = controlState(parentWorkflowId)
    val current = state.executionLease ?: return false
    if (current.ownerToken != ownerToken || current.generation != generation) return false
    persistControlState(parentWorkflowId, state.copy(executionLease = null))
    return true
  }
}

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository
