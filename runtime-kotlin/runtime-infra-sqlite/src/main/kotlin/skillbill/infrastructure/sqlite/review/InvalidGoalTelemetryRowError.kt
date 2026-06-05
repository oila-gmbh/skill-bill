package skillbill.infrastructure.sqlite.review

import skillbill.error.ShellContentContractException

/**
 * SKILL-66 Subtask 2: surfaced when a persisted goal telemetry row
 * (`goal_run_sessions` / `goal_subtask_events`) is malformed on the stats read
 * path — a missing/null required column, a non-numeric value, a negative
 * duration/count, an out-of-range subtask id, or a status outside the goal
 * enums. The message carries the offending row identity and column so the
 * read seam fails loudly instead of best-effort parsing or silent truncation
 * (AC#5). Mirrors the typed schema-error family in
 * [skillbill.error.ShellContentContractErrors]; lives in the infra-sqlite
 * module because it never crosses the port boundary.
 */
class InvalidGoalTelemetryRowError(
  val rowIdentity: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal telemetry row $rowIdentity is malformed: $reason",
  cause,
)
