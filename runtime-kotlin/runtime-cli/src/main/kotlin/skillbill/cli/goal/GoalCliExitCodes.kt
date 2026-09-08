package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerStopStatus

internal const val GOAL_EXIT_COMPLETE: Int = 0
internal const val GOAL_EXIT_FAILED: Int = 1
internal const val GOAL_EXIT_PAUSED: Int = 2
internal const val GOAL_EXIT_BLOCKED: Int = 3

internal fun goalRunExitCode(status: String?, reason: String?): Int {
  if (status == "complete") return GOAL_EXIT_COMPLETE
  val normalized = reason?.lowercase().orEmpty()
  return when {
    normalized == "paused" -> GOAL_EXIT_PAUSED
    normalized.contains("failed") || normalized.contains("timeout") -> GOAL_EXIT_FAILED
    else -> GOAL_EXIT_BLOCKED
  }
}

internal fun Map<String, Any?>.goalExitCode(): Int =
  goalRunExitCode(this["status"]?.toString(), this["reason"]?.toString())

internal fun Map<String, Any?>.goalStatusExitCode(): Int = if (!containsKey(
    "status",
  ) || this["status"] == "ok"
) {
  0
} else {
  1
}

internal fun Map<String, Any?>.goalPauseExitCode(): Int = if (this["status"] != "not_found") 0 else 1

// Idempotent outcomes exit 0; a refused stop is a non-zero failure the operator must act on.
internal fun Map<String, Any?>.goalStopExitCode(): Int = when (this["status"]) {
  GoalRunnerStopStatus.STOPPED.wireValue,
  GoalRunnerStopStatus.ALREADY_STOPPED.wireValue,
  GoalRunnerStopStatus.NO_LIVE_LEASE.wireValue,
  -> 0
  else -> 1
}
