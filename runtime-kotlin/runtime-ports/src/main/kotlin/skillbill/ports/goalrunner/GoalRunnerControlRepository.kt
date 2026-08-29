package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

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
    persistControlState(
      parentWorkflowId,
      state.copy(
        executionLease = lease,
        activeDurationAsOf = lease.heartbeatAt,
        subtaskActiveDurationAsOf = lease.heartbeatAt.takeIf { state.currentSubtaskId != null },
      ),
    )
    return true
  }

  fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean {
    val state = controlState(parentWorkflowId)
    val current = state.executionLease ?: return false
    if (current.ownerToken != lease.ownerToken || current.generation != lease.generation) return false
    persistControlState(parentWorkflowId, state.advancedBy(lease.heartbeatAt).copy(executionLease = lease))
    return true
  }

  fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean {
    val state = controlState(parentWorkflowId)
    val current = state.executionLease ?: return false
    if (current.ownerToken != ownerToken || current.generation != generation) return false
    // The tail since the last heartbeat is dropped rather than trusted: release carries no clock, and
    // an unbounded tail is exactly what this accumulator exists to keep out. It is under one interval.
    persistControlState(
      parentWorkflowId,
      state.copy(executionLease = null, activeDurationAsOf = null, subtaskActiveDurationAsOf = null),
    )
    return true
  }
}

/**
 * Folds the interval since [GoalRunnerControlState.activeDurationAsOf] into the active total.
 *
 * The interval is capped at [GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS] rather than discarded when it runs
 * long. A gap that wide means the runner was not alive for all of it -- a killed run whose lease was
 * later reacquired -- so the excess is downtime and must not be counted; but a merely late tick from
 * a GC pause or a contended write still covers real execution, and dropping it outright would make
 * the accumulator undercount exactly when the machine is busiest. Capping keeps downtime out while
 * crediting at most one interval of work. An unparseable or backwards timestamp advances nothing and
 * only re-anchors the marker.
 */
private fun GoalRunnerControlState.advancedBy(heartbeatAt: String): GoalRunnerControlState {
  val goal = advanceAccumulator(activeDurationMs, activeDurationAsOf, heartbeatAt)
  val subtask = if (currentSubtaskId != null) {
    advanceAccumulator(subtaskActiveDurationMs, subtaskActiveDurationAsOf, heartbeatAt)
  } else {
    subtaskActiveDurationMs to subtaskActiveDurationAsOf
  }
  return copy(
    activeDurationMs = goal.first,
    activeDurationAsOf = goal.second,
    subtaskActiveDurationMs = subtask.first,
    subtaskActiveDurationAsOf = subtask.second,
  )
}

private fun advanceAccumulator(accumulatedMs: Long, asOf: String?, heartbeatAt: String): Pair<Long, String?> {
  val previous = asOf ?: return accumulatedMs to heartbeatAt
  val elapsedMs = runCatching {
    java.time.Duration.between(java.time.Instant.parse(previous), java.time.Instant.parse(heartbeatAt)).toMillis()
  }.getOrNull() ?: return accumulatedMs to heartbeatAt
  val counted = elapsedMs.coerceIn(0, GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS)
  return accumulatedMs + counted to heartbeatAt
}

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository
