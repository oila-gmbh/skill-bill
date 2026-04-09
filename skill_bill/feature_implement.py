from __future__ import annotations

import json
import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  AUDIT_RESULTS,
  BOUNDARY_HISTORY_VALUES,
  COMPLETION_STATUSES,
  EVENT_FEATURE_IMPLEMENT_FINISHED,
  EVENT_FEATURE_IMPLEMENT_STARTED,
  FEATURE_FLAG_PATTERNS,
  FEATURE_SIZES,
  ISSUE_KEY_TYPES,
  SPEC_INPUT_TYPES,
  VALIDATION_RESULTS,
)
from skill_bill.stats import enqueue_telemetry_event


def generate_feature_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"fis-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_enum(value: str, allowed: tuple[str, ...], field_name: str) -> str | None:
  if value not in allowed:
    return f"Invalid {field_name} '{value}'. Allowed: {', '.join(allowed)}"
  return None


def validate_started_params(
  *,
  feature_size: str,
  issue_key_type: str,
  spec_input_types: list[str],
) -> str | None:
  error = validate_enum(feature_size, FEATURE_SIZES, "feature_size")
  if error:
    return error
  error = validate_enum(issue_key_type, ISSUE_KEY_TYPES, "issue_key_type")
  if error:
    return error
  for spec_type in spec_input_types:
    error = validate_enum(spec_type, SPEC_INPUT_TYPES, "spec_input_types")
    if error:
      return error
  return None


def validate_finished_params(
  *,
  completion_status: str,
  feature_flag_pattern: str,
  audit_result: str,
  validation_result: str,
  boundary_history_value: str,
) -> str | None:
  error = validate_enum(completion_status, COMPLETION_STATUSES, "completion_status")
  if error:
    return error
  error = validate_enum(feature_flag_pattern, FEATURE_FLAG_PATTERNS, "feature_flag_pattern")
  if error:
    return error
  error = validate_enum(audit_result, AUDIT_RESULTS, "audit_result")
  if error:
    return error
  error = validate_enum(validation_result, VALIDATION_RESULTS, "validation_result")
  if error:
    return error
  error = validate_enum(boundary_history_value, BOUNDARY_HISTORY_VALUES, "boundary_history_value")
  if error:
    return error
  return None


def save_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  issue_key_provided: bool,
  issue_key_type: str,
  spec_input_types: list[str],
  spec_word_count: int,
  feature_size: str,
  feature_name: str,
  rollout_needed: bool,
  acceptance_criteria_count: int,
  open_questions_count: int,
  spec_summary: str,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO feature_implement_sessions (
        session_id, issue_key_provided, issue_key_type, spec_input_types,
        spec_word_count, feature_size, feature_name, rollout_needed,
        acceptance_criteria_count, open_questions_count, spec_summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      (
        session_id,
        1 if issue_key_provided else 0,
        issue_key_type,
        json.dumps(spec_input_types),
        spec_word_count,
        feature_size,
        feature_name,
        1 if rollout_needed else 0,
        acceptance_criteria_count,
        open_questions_count,
        spec_summary,
      ),
    )


def save_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  completion_status: str,
  plan_correction_count: int,
  plan_task_count: int,
  plan_phase_count: int,
  feature_flag_used: bool,
  feature_flag_pattern: str,
  files_created: int,
  files_modified: int,
  tasks_completed: int,
  review_iterations: int,
  audit_result: str,
  audit_iterations: int,
  validation_result: str,
  boundary_history_written: bool,
  boundary_history_value: str,
  pr_created: bool,
  plan_deviation_notes: str,
) -> None:
  exists = connection.execute(
    "SELECT 1 FROM feature_implement_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()

  if exists:
    with connection:
      connection.execute(
        """
        UPDATE feature_implement_sessions SET
          completion_status = ?,
          plan_correction_count = ?,
          plan_task_count = ?,
          plan_phase_count = ?,
          feature_flag_used = ?,
          feature_flag_pattern = ?,
          files_created = ?,
          files_modified = ?,
          tasks_completed = ?,
          review_iterations = ?,
          audit_result = ?,
          audit_iterations = ?,
          validation_result = ?,
          boundary_history_written = ?,
          boundary_history_value = ?,
          pr_created = ?,
          plan_deviation_notes = ?,
          finished_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (
          completion_status,
          plan_correction_count,
          plan_task_count,
          plan_phase_count,
          1 if feature_flag_used else 0,
          feature_flag_pattern,
          files_created,
          files_modified,
          tasks_completed,
          review_iterations,
          audit_result,
          audit_iterations,
          validation_result,
          1 if boundary_history_written else 0,
          boundary_history_value,
          1 if pr_created else 0,
          plan_deviation_notes,
          session_id,
        ),
      )
  else:
    with connection:
      connection.execute(
        """
        INSERT INTO feature_implement_sessions (
          session_id, completion_status, plan_correction_count,
          plan_task_count, plan_phase_count, feature_flag_used,
          feature_flag_pattern, files_created, files_modified,
          tasks_completed, review_iterations, audit_result,
          audit_iterations, validation_result, boundary_history_written,
          boundary_history_value, pr_created, plan_deviation_notes,
          finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        (
          session_id,
          completion_status,
          plan_correction_count,
          plan_task_count,
          plan_phase_count,
          1 if feature_flag_used else 0,
          feature_flag_pattern,
          files_created,
          files_modified,
          tasks_completed,
          review_iterations,
          audit_result,
          audit_iterations,
          validation_result,
          1 if boundary_history_written else 0,
          boundary_history_value,
          1 if pr_created else 0,
          plan_deviation_notes,
        ),
      )


def fetch_session(connection: sqlite3.Connection, session_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_implement_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()


def build_started_payload(
  connection: sqlite3.Connection,
  session_id: str,
  level: str,
) -> dict[str, object]:
  row = fetch_session(connection, session_id)
  if row is None:
    return {}

  payload: dict[str, object] = {
    "session_id": row["session_id"],
    "issue_key_provided": bool(row["issue_key_provided"]),
    "issue_key_type": row["issue_key_type"],
    "spec_input_types": json.loads(row["spec_input_types"] or "[]"),
    "spec_word_count": row["spec_word_count"],
    "feature_size": row["feature_size"],
    "rollout_needed": bool(row["rollout_needed"]),
    "acceptance_criteria_count": row["acceptance_criteria_count"],
    "open_questions_count": row["open_questions_count"],
  }

  if level == "full":
    payload["feature_name"] = row["feature_name"]
    payload["spec_summary"] = row["spec_summary"]

  return payload


def build_finished_payload(
  connection: sqlite3.Connection,
  session_id: str,
  level: str,
) -> dict[str, object]:
  row = fetch_session(connection, session_id)
  if row is None:
    return {}

  payload = build_started_payload(connection, session_id, level)

  started_at = row["started_at"] or ""
  finished_at = row["finished_at"] or ""
  duration_seconds = 0
  if started_at and finished_at:
    try:
      start_dt = datetime.fromisoformat(started_at)
      end_dt = datetime.fromisoformat(finished_at)
      duration_seconds = max(0, int((end_dt - start_dt).total_seconds()))
    except (ValueError, TypeError):
      pass

  payload.update({
    "completion_status": row["completion_status"] or "",
    "plan_correction_count": row["plan_correction_count"] or 0,
    "plan_task_count": row["plan_task_count"] or 0,
    "plan_phase_count": row["plan_phase_count"] or 0,
    "feature_flag_used": bool(row["feature_flag_used"]),
    "feature_flag_pattern": row["feature_flag_pattern"] or "none",
    "files_created": row["files_created"] or 0,
    "files_modified": row["files_modified"] or 0,
    "tasks_completed": row["tasks_completed"] or 0,
    "review_iterations": row["review_iterations"] or 0,
    "audit_result": row["audit_result"] or "skipped",
    "audit_iterations": row["audit_iterations"] or 0,
    "validation_result": row["validation_result"] or "skipped",
    "boundary_history_written": bool(row["boundary_history_written"]),
    "boundary_history_value": row["boundary_history_value"] or "none",
    "pr_created": bool(row["pr_created"]),
    "duration_seconds": duration_seconds,
  })

  if level == "full":
    payload["plan_deviation_notes"] = row["plan_deviation_notes"] or ""

  return payload


def emit_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  enabled: bool,
  level: str,
) -> None:
  row = fetch_session(connection, session_id)
  if row is None:
    return
  if row["started_event_emitted_at"]:
    return

  payload = build_started_payload(connection, session_id, level)
  with connection:
    enqueue_telemetry_event(
      connection,
      event_name=EVENT_FEATURE_IMPLEMENT_STARTED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_implement_sessions
        SET started_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )


def emit_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  enabled: bool,
  level: str,
) -> None:
  row = fetch_session(connection, session_id)
  if row is None:
    return
  if row["finished_event_emitted_at"]:
    return

  payload = build_finished_payload(connection, session_id, level)
  with connection:
    enqueue_telemetry_event(
      connection,
      event_name=EVENT_FEATURE_IMPLEMENT_FINISHED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_implement_sessions
        SET finished_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )
