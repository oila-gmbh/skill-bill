package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import java.time.Duration
import java.time.Instant

fun GoalRunnerControlStateRepository.executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
  controlState(parentWorkflowId).executionLease

fun GoalRunnerControlStateRepository.acquireExecutionLease(
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

fun GoalRunnerControlStateRepository.heartbeatExecutionLease(
  parentWorkflowId: String,
  lease: GoalRunnerExecutionLease,
): Boolean {
  val state = controlState(parentWorkflowId)
  val current = state.executionLease ?: return false
  if (current.ownerToken != lease.ownerToken || current.generation != lease.generation) return false
  persistControlState(parentWorkflowId, state.advancedBy(lease.heartbeatAt).copy(executionLease = lease))
  return true
}

fun GoalRunnerControlStateRepository.releaseExecutionLease(
  parentWorkflowId: String,
  ownerToken: String,
  generation: Long,
): Boolean {
  val state = controlState(parentWorkflowId)
  val current = state.executionLease ?: return false
  if (current.ownerToken != ownerToken || current.generation != generation) return false
  persistControlState(
    parentWorkflowId,
    state.copy(executionLease = null, activeDurationAsOf = null, subtaskActiveDurationAsOf = null),
  )
  return true
}

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
    Duration.between(Instant.parse(previous), Instant.parse(heartbeatAt)).toMillis()
  }.getOrNull() ?: return accumulatedMs to heartbeatAt
  val counted = elapsedMs.coerceIn(0, GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS)
  return accumulatedMs + counted to heartbeatAt
}
