package skillbill.db.workflow

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerControlState

internal fun decodeControlState(raw: String): GoalRunnerControlState {
  val state = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: goalRunnerControlSchemaError("durable record must be an object.")
  val allowedKeys = setOf(
    "stop_after_subtask_id",
    "pause_requested",
    "pause_consumed",
    "paused",
    "pause_reason",
    "paused_at",
    "stop_after_consumed",
    "repository_identity",
    "execution_lease",
    "active_duration_ms",
    "active_duration_as_of",
    "current_subtask_id",
    "subtask_active_duration_ms",
    "subtask_active_duration_as_of",
  )
  state.keys.forEach { key ->
    if (key !in allowedKeys) {
      goalRunnerControlSchemaError("has unsupported field '$key'.")
    }
  }
  return GoalRunnerControlState(
    stopAfterSubtaskId = state["stop_after_subtask_id"].toPositiveIntOrNull("stop_after_subtask_id"),
    pauseRequested = state.booleanOrDefault("pause_requested", false),
    pauseConsumed = state.booleanOrDefault("pause_consumed", false),
    paused = state.booleanOrDefault("paused", false),
    pauseReason = state.nullableString("pause_reason"),
    pausedAt = state.nullableString("paused_at") ?: legacyPausedAt(state),
    stopAfterConsumed = state.booleanOrDefault("stop_after_consumed", false),
    repositoryIdentity = state.nullableString("repository_identity"),
    executionLease = state["execution_lease"]?.let(::decodeExecutionLease),
    activeDurationMs = state.nonNegativeLongOrDefault("active_duration_ms"),
    activeDurationAsOf = state.nullableString("active_duration_as_of"),
    currentSubtaskId = state["current_subtask_id"].toPositiveIntOrNull("current_subtask_id"),
    subtaskActiveDurationMs = state.nonNegativeLongOrDefault("subtask_active_duration_ms"),
    subtaskActiveDurationAsOf = state.nullableString("subtask_active_duration_as_of"),
  )
}
