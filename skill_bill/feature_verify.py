from __future__ import annotations

import json
import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  AUDIT_RESULTS,
  EVENT_FEATURE_VERIFY_FINISHED,
  EVENT_FEATURE_VERIFY_STARTED,
  FEATURE_VERIFY_COMPLETION_STATUSES,
  FEATURE_VERIFY_SESSION_PREFIX,
)
from skill_bill.feature_implement import validate_enum
from skill_bill.stats import enqueue_telemetry_event


def generate_feature_verify_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{FEATURE_VERIFY_SESSION_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_finished_params(
  *,
  audit_result: str,
  completion_status: str,
) -> str | None:
  error = validate_enum(audit_result, AUDIT_RESULTS, "audit_result")
  if error:
    return error
  return validate_enum(
    completion_status,
    FEATURE_VERIFY_COMPLETION_STATUSES,
    "completion_status",
  )


def save_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO feature_verify_sessions (
        session_id, acceptance_criteria_count, rollout_relevant, spec_summary
      ) VALUES (?, ?, ?, ?)
      """,
      (
        session_id,
        acceptance_criteria_count,
        1 if rollout_relevant else 0,
        spec_summary,
      ),
    )


def save_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  feature_flag_audit_performed: bool,
  review_iterations: int,
  audit_result: str,
  completion_status: str,
  gaps_found: list[str],
) -> None:
  exists = connection.execute(
    "SELECT 1 FROM feature_verify_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()

  if exists:
    with connection:
      connection.execute(
        """
        UPDATE feature_verify_sessions SET
          feature_flag_audit_performed = ?,
          review_iterations = ?,
          audit_result = ?,
          completion_status = ?,
          gaps_found = ?,
          finished_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (
          1 if feature_flag_audit_performed else 0,
          review_iterations,
          audit_result,
          completion_status,
          json.dumps(gaps_found),
          session_id,
        ),
      )
  else:
    with connection:
      connection.execute(
        """
        INSERT INTO feature_verify_sessions (
          session_id, feature_flag_audit_performed, review_iterations,
          audit_result, completion_status, gaps_found, finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        (
          session_id,
          1 if feature_flag_audit_performed else 0,
          review_iterations,
          audit_result,
          completion_status,
          json.dumps(gaps_found),
        ),
      )


def fetch_session(connection: sqlite3.Connection, session_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_verify_sessions WHERE session_id = ?",
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
  return build_started_payload_from_fields(
    session_id=row["session_id"],
    acceptance_criteria_count=row["acceptance_criteria_count"],
    rollout_relevant=bool(row["rollout_relevant"]),
    spec_summary=row["spec_summary"],
    level=level,
  )


def build_started_payload_from_fields(
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
  level: str,
) -> dict[str, object]:
  payload: dict[str, object] = {
    "session_id": session_id,
    "acceptance_criteria_count": acceptance_criteria_count,
    "rollout_relevant": rollout_relevant,
  }
  if level == "full":
    payload["spec_summary"] = spec_summary
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

  gaps_found = json.loads(row["gaps_found"] or "[]")

  payload.update({
    "feature_flag_audit_performed": bool(row["feature_flag_audit_performed"]),
    "review_iterations": row["review_iterations"] or 0,
    "audit_result": row["audit_result"] or "skipped",
    "completion_status": row["completion_status"] or "",
    "duration_seconds": duration_seconds,
  })

  if level == "full":
    payload["gaps_found"] = gaps_found

  return payload


def build_finished_payload_from_fields(
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
  feature_flag_audit_performed: bool,
  review_iterations: int,
  audit_result: str,
  completion_status: str,
  gaps_found: list[str],
  duration_seconds: int,
  level: str,
) -> dict[str, object]:
  payload = build_started_payload_from_fields(
    session_id=session_id,
    acceptance_criteria_count=acceptance_criteria_count,
    rollout_relevant=rollout_relevant,
    spec_summary=spec_summary,
    level=level,
  )
  payload.update({
    "feature_flag_audit_performed": feature_flag_audit_performed,
    "review_iterations": review_iterations,
    "audit_result": audit_result,
    "completion_status": completion_status,
    "duration_seconds": duration_seconds,
  })
  if level == "full":
    payload["gaps_found"] = list(gaps_found)
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
      event_name=EVENT_FEATURE_VERIFY_STARTED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_verify_sessions
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
      event_name=EVENT_FEATURE_VERIFY_FINISHED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_verify_sessions
        SET finished_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )
