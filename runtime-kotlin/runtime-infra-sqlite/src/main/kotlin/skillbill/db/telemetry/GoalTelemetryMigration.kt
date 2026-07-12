package skillbill.db.telemetry

import java.sql.Connection

/**
 * SKILL-66 Subtask 2: creates the goal telemetry tables for the
 * `goal_started`/`goal_subtask_finished`/`goal_finished` event family.
 *
 * `goal_run_sessions` is keyed by `workflow_id` (one row per run segment);
 * finished columns are nullable until the run reaches a terminal outcome,
 * mirroring the `*_event_emitted_at` nullability of existing lifecycle tables.
 * `goal_subtask_events` enforces the parent-spec resume-dedupe identity
 * `(issue_key, subtask_id, workflow_id)` as its composite PRIMARY KEY so a
 * resumed run cannot double-count a subtask (the store inserts ON CONFLICT
 * DO NOTHING). Emitter-minted timestamps/durations are persisted verbatim;
 * the separate `*_event_emitted_at` columns serve outbox idempotency.
 */
internal object GoalTelemetryMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_RUN_SESSIONS_SQL)
    }
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_SUBTASK_EVENTS_SQL)
    }
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_ISSUE_PROGRESS_SQL)
    }
  }

  private const val CREATE_GOAL_RUN_SESSIONS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_run_sessions (
      workflow_id TEXT PRIMARY KEY,
      issue_key TEXT NOT NULL,
      feature_name TEXT NOT NULL DEFAULT '',
      subtask_total INTEGER NOT NULL DEFAULT 0,
      resumed INTEGER NOT NULL DEFAULT 0,
      started_at TEXT NOT NULL DEFAULT '',
      started_event_emitted_at TEXT,
      status TEXT,
      finished_at TEXT,
      finished_duration_ms INTEGER,
      subtasks_complete INTEGER,
      subtasks_blocked INTEGER,
      subtasks_skipped INTEGER,
      finished_event_emitted_at TEXT
    )
    """

  private const val CREATE_GOAL_SUBTASK_EVENTS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_subtask_events (
      issue_key TEXT NOT NULL,
      workflow_id TEXT NOT NULL,
      subtask_id INTEGER NOT NULL,
      subtask_name TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL,
      started_at TEXT NOT NULL,
      finished_at TEXT NOT NULL,
      duration_ms INTEGER NOT NULL,
      attempt_count INTEGER NOT NULL,
      blocked_reason TEXT,
      finalizing_agent_id TEXT,
      participating_agent_ids TEXT NOT NULL DEFAULT '[]',
      subtask_event_emitted_at TEXT,
      PRIMARY KEY (issue_key, subtask_id, workflow_id)
    )
    """

  private const val CREATE_GOAL_ISSUE_PROGRESS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_issue_progress (
      parent_workflow_id TEXT NOT NULL,
      issue_key TEXT NOT NULL,
      total_invocations INTEGER NOT NULL DEFAULT 0,
      total_blocks INTEGER NOT NULL DEFAULT 0,
      total_resumes INTEGER NOT NULL DEFAULT 0,
      first_started_at TEXT,
      finished_at TEXT,
      status TEXT,
      subtasks_complete INTEGER,
      subtasks_blocked INTEGER,
      subtasks_skipped INTEGER,
      mode TEXT NOT NULL DEFAULT 'runtime',
      finished_event_emitted_at TEXT,
      PRIMARY KEY (parent_workflow_id, issue_key)
    )
    """
}
