from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
import os
import re
import sqlite3

from skill_bill.constants import (
  DB_ENVIRONMENT_KEY,
  DEFAULT_DB_PATH,
  FINDING_OUTCOME_TYPES,
)


def resolve_db_path(cli_value: str | None) -> Path:
  candidate = cli_value or os.environ.get(DB_ENVIRONMENT_KEY)
  if candidate:
    return Path(candidate).expanduser().resolve()
  return DEFAULT_DB_PATH.expanduser().resolve()


@contextmanager
def open_db(cli_value: str | None = None, *, sync: bool = True):
  from skill_bill.sync import auto_sync_telemetry
  db_path = resolve_db_path(cli_value)
  connection = ensure_database(db_path)
  try:
    yield connection, db_path
  finally:
    connection.close()
  if sync:
    auto_sync_telemetry(db_path)


def ensure_database(path: Path) -> sqlite3.Connection:
  path.parent.mkdir(parents=True, exist_ok=True)
  connection = sqlite3.connect(path)
  connection.execute("PRAGMA foreign_keys = ON")
  connection.row_factory = sqlite3.Row
  connection.executescript(
    """
    CREATE TABLE IF NOT EXISTS review_runs (
      review_run_id TEXT PRIMARY KEY,
      review_session_id TEXT,
      routed_skill TEXT,
      detected_scope TEXT,
      detected_stack TEXT,
      execution_mode TEXT,
      source_path TEXT,
      raw_text TEXT NOT NULL,
      review_finished_at TEXT,
      review_finished_event_emitted_at TEXT,
      imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS findings (
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      severity TEXT NOT NULL,
      confidence TEXT NOT NULL,
      location TEXT NOT NULL,
      description TEXT NOT NULL,
      finding_text TEXT NOT NULL,
      PRIMARY KEY (review_run_id, finding_id),
      FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS feedback_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      event_type TEXT NOT NULL CHECK (
        event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
      ),
      note TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_feedback_events_run
      ON feedback_events(review_run_id, finding_id, id);

    CREATE TABLE IF NOT EXISTS learnings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      scope TEXT NOT NULL CHECK (scope IN ('global', 'repo', 'skill')),
      scope_key TEXT NOT NULL DEFAULT '',
      title TEXT NOT NULL,
      rule_text TEXT NOT NULL,
      rationale TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL CHECK (status IN ('active', 'disabled')) DEFAULT 'active',
      source_review_run_id TEXT,
      source_finding_id TEXT,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      CHECK ((source_review_run_id IS NULL) = (source_finding_id IS NULL)),
      FOREIGN KEY (source_review_run_id, source_finding_id)
        REFERENCES findings(review_run_id, finding_id)
        ON DELETE SET NULL
    );

    CREATE INDEX IF NOT EXISTS idx_learnings_scope
      ON learnings(scope, scope_key, status);

    CREATE TABLE IF NOT EXISTS telemetry_outbox (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_name TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      synced_at TEXT,
      last_error TEXT NOT NULL DEFAULT ''
    );

    CREATE INDEX IF NOT EXISTS idx_telemetry_outbox_pending
      ON telemetry_outbox(synced_at, id);

    CREATE TABLE IF NOT EXISTS session_learnings (
      review_session_id TEXT PRIMARY KEY,
      learnings_json TEXT NOT NULL,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS quality_check_sessions (
      session_id TEXT PRIMARY KEY,
      routed_skill TEXT NOT NULL DEFAULT '',
      detected_stack TEXT NOT NULL DEFAULT '',
      scope_type TEXT NOT NULL DEFAULT '',
      initial_failure_count INTEGER NOT NULL DEFAULT 0,
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      started_event_emitted_at TEXT,
      final_failure_count INTEGER,
      iterations INTEGER,
      result TEXT,
      failing_check_names TEXT NOT NULL DEFAULT '',
      unsupported_reason TEXT NOT NULL DEFAULT '',
      finished_at TEXT,
      finished_event_emitted_at TEXT
    );

    CREATE TABLE IF NOT EXISTS feature_verify_sessions (
      session_id TEXT PRIMARY KEY,
      acceptance_criteria_count INTEGER NOT NULL DEFAULT 0,
      rollout_relevant INTEGER NOT NULL DEFAULT 0,
      spec_summary TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      started_event_emitted_at TEXT,
      feature_flag_audit_performed INTEGER,
      review_iterations INTEGER,
      audit_result TEXT,
      completion_status TEXT,
      gaps_found TEXT NOT NULL DEFAULT '',
      finished_at TEXT,
      finished_event_emitted_at TEXT
    );

    CREATE TABLE IF NOT EXISTS feature_implement_sessions (
      session_id TEXT PRIMARY KEY,
      issue_key_provided INTEGER NOT NULL DEFAULT 0,
      issue_key_type TEXT NOT NULL DEFAULT 'none',
      spec_input_types TEXT NOT NULL DEFAULT '',
      spec_word_count INTEGER NOT NULL DEFAULT 0,
      feature_size TEXT NOT NULL DEFAULT 'SMALL',
      feature_name TEXT NOT NULL DEFAULT '',
      rollout_needed INTEGER NOT NULL DEFAULT 0,
      acceptance_criteria_count INTEGER NOT NULL DEFAULT 0,
      open_questions_count INTEGER NOT NULL DEFAULT 0,
      spec_summary TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      started_event_emitted_at TEXT,
      completion_status TEXT NOT NULL DEFAULT '',
      plan_correction_count INTEGER,
      plan_task_count INTEGER,
      plan_phase_count INTEGER,
      feature_flag_used INTEGER,
      feature_flag_pattern TEXT,
      files_created INTEGER,
      files_modified INTEGER,
      tasks_completed INTEGER,
      review_iterations INTEGER,
      audit_result TEXT,
      audit_iterations INTEGER,
      validation_result TEXT,
      boundary_history_written INTEGER,
      boundary_history_value TEXT NOT NULL DEFAULT 'none',
      pr_created INTEGER,
      plan_deviation_notes TEXT NOT NULL DEFAULT '',
      finished_at TEXT,
      finished_event_emitted_at TEXT
    );

    CREATE TABLE IF NOT EXISTS feature_implement_workflows (
      workflow_id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL DEFAULT '',
      workflow_name TEXT NOT NULL DEFAULT 'bill-feature-implement',
      contract_version TEXT NOT NULL DEFAULT '0.1',
      workflow_status TEXT NOT NULL DEFAULT 'pending',
      current_step_id TEXT NOT NULL DEFAULT '',
      steps_json TEXT NOT NULL DEFAULT '',
      artifacts_json TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      finished_at TEXT
    );
    """
  )
  ensure_column(connection, "review_runs", "review_session_id", "TEXT")
  ensure_column(connection, "review_runs", "review_finished_at", "TEXT")
  ensure_column(connection, "review_runs", "review_finished_event_emitted_at", "TEXT")
  ensure_column(connection, "review_runs", "specialist_reviews", "TEXT NOT NULL DEFAULT ''")
  ensure_column(connection, "review_runs", "orchestrated_run", "INTEGER NOT NULL DEFAULT 0")
  backfill_review_session_ids(connection)
  ensure_column(
    connection,
    "feature_implement_sessions",
    "boundary_history_value",
    "TEXT NOT NULL DEFAULT 'none'",
  )
  ensure_column(
    connection,
    "feature_implement_sessions",
    "child_steps_json",
    "TEXT NOT NULL DEFAULT ''",
  )
  migrate_feedback_events_schema(connection)
  return connection


SAFE_IDENTIFIER_PATTERN = re.compile(r"^[a-z_][a-z0-9_]*$")


def ensure_column(
  connection: sqlite3.Connection,
  table_name: str,
  column_name: str,
  definition: str,
) -> None:
  if not SAFE_IDENTIFIER_PATTERN.match(table_name):
    raise ValueError(f"Unsafe table name: '{table_name}'")
  if not SAFE_IDENTIFIER_PATTERN.match(column_name):
    raise ValueError(f"Unsafe column name: '{column_name}'")
  columns = connection.execute(f"PRAGMA table_info({table_name})").fetchall()
  if any(str(column["name"]) == column_name for column in columns):
    return
  with connection:
    connection.execute(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {definition}")


def backfill_review_session_ids(connection: sqlite3.Connection) -> None:
  with connection:
    connection.execute(
      """
      UPDATE review_runs
      SET review_session_id = review_run_id
      WHERE review_session_id IS NULL OR review_session_id = ''
      """
    )


def migrate_feedback_events_schema(connection: sqlite3.Connection) -> None:
  row = connection.execute(
    """
    SELECT sql
    FROM sqlite_master
    WHERE type = 'table' AND name = 'feedback_events'
    """
  ).fetchone()
  if row is None:
    return

  create_sql = str(row["sql"] or "")
  has_current_schema = all(f"'{event_type}'" in create_sql for event_type in FINDING_OUTCOME_TYPES)
  has_legacy_schema = any(
    legacy_event in create_sql
    for legacy_event in ("'accepted'", "'dismissed'", "'fix_requested'")
  )
  if has_current_schema and not has_legacy_schema:
    return

  rows = connection.execute(
    """
    SELECT id, review_run_id, finding_id, event_type, note, created_at
    FROM feedback_events
    ORDER BY id
    """
  ).fetchall()
  migrated_rows = [
    (
      int(row["id"]),
      str(row["review_run_id"]),
      str(row["finding_id"]),
      normalize_feedback_event_type(str(row["event_type"])),
      str(row["note"] or ""),
      str(row["created_at"]),
    )
    for row in rows
  ]

  with connection:
    connection.execute("ALTER TABLE feedback_events RENAME TO feedback_events_legacy")
    connection.executescript(
      """
      CREATE TABLE feedback_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        event_type TEXT NOT NULL CHECK (
          event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
        ),
        note TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_feedback_events_run
        ON feedback_events(review_run_id, finding_id, id);
      """
    )
    if migrated_rows:
      connection.executemany(
        """
        INSERT INTO feedback_events (id, review_run_id, finding_id, event_type, note, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        migrated_rows,
      )
    connection.execute("DROP TABLE feedback_events_legacy")


def normalize_feedback_event_type(event_type: str) -> str:
  legacy_mapping = {
    "accepted": "finding_accepted",
    "dismissed": "fix_rejected",
    "fix_requested": "fix_applied",
  }
  normalized_event_type = legacy_mapping.get(event_type, event_type)
  if normalized_event_type not in FINDING_OUTCOME_TYPES:
    raise ValueError(f"Unsupported finding outcome '{event_type}'.")
  return normalized_event_type


def review_exists(connection: sqlite3.Connection, review_run_id: str) -> bool:
  row = connection.execute(
    "SELECT 1 FROM review_runs WHERE review_run_id = ?",
    (review_run_id,),
  ).fetchone()
  return row is not None


def finding_exists(connection: sqlite3.Connection, review_run_id: str, finding_id: str) -> bool:
  row = connection.execute(
    """
    SELECT 1
    FROM findings
    WHERE review_run_id = ? AND finding_id = ?
    """,
    (review_run_id, finding_id),
  ).fetchone()
  return row is not None
