package skillbill.cli.goal

import skillbill.cli.core.requireInvokingAgentId

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

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

internal fun resolveInvokedAgentId(explicitAgent: String?, environment: Map<String, String>): String =
  requireInvokingAgentId(explicitAgent, environment, "--agent")

internal const val DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES = 10
/**
 * Default per-subtask wall-clock cap. Chosen from local goal_subtask_events telemetry:
 * p95 ≈ 96m, p99 ≈ 142m, observed max ≈ 178m. A live forever-command (e.g. attached
 * `docker compose up`) is spared by the progress-idle timeout, so this hard ceiling is
 * what stops unbounded waits. Pass 0 on the CLI to disable.
 */
internal const val DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES = 180
internal const val DEFAULT_GOAL_WATCH_INTERVAL_SECONDS = 5
internal const val DEFAULT_GOAL_WATCH_REFRESHES = 0
internal const val IDLE_STOP_CONSECUTIVE_REFRESHES = 3
internal const val MILLIS_PER_SECOND = 1_000L
internal const val RUNTIME_EXECUTABLE_ENV = "SKILL_BILL_RUNTIME_EXECUTABLE"
internal const val RUNTIME_CLASSPATH_ENV = "SKILL_BILL_RUNTIME_CLASSPATH"
internal const val RUNTIME_PATH_SEPARATOR_ENV = "SKILL_BILL_PATH_SEPARATOR"
