from __future__ import annotations

import json
import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  EVENT_QUALITY_CHECK_FINISHED,
  EVENT_QUALITY_CHECK_STARTED,
  QUALITY_CHECK_RESULTS,
  QUALITY_CHECK_SCOPE_TYPES,
  QUALITY_CHECK_SESSION_PREFIX,
)
from skill_bill.feature_implement import validate_enum
from skill_bill.stats import enqueue_telemetry_event


def generate_quality_check_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{QUALITY_CHECK_SESSION_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_started_params(*, scope_type: str) -> str | None:
  return validate_enum(scope_type, QUALITY_CHECK_SCOPE_TYPES, "scope_type")


def validate_finished_params(*, result: str) -> str | None:
  return validate_enum(result, QUALITY_CHECK_RESULTS, "result")


def save_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  routed_skill: str,
  detected_stack: str,
  scope_type: str,
  initial_failure_count: int,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO quality_check_sessions (
        session_id, routed_skill, detected_stack, scope_type, initial_failure_count
      ) VALUES (?, ?, ?, ?, ?)
      """,
      (
        session_id,
        routed_skill,
        detected_stack,
        scope_type,
        initial_failure_count,
      ),
    )


def save_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  final_failure_count: int,
  iterations: int,
  result: str,
  failing_check_names: list[str],
  unsupported_reason: str,
) -> None:
  exists = connection.execute(
    "SELECT 1 FROM quality_check_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()

  if exists:
    with connection:
      connection.execute(
        """
        UPDATE quality_check_sessions SET
          final_failure_count = ?,
          iterations = ?,
          result = ?,
          failing_check_names = ?,
          unsupported_reason = ?,
          finished_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (
          final_failure_count,
          iterations,
          result,
          json.dumps(failing_check_names),
          unsupported_reason,
          session_id,
        ),
      )
  else:
    with connection:
      connection.execute(
        """
        INSERT INTO quality_check_sessions (
          session_id, final_failure_count, iterations, result,
          failing_check_names, unsupported_reason, finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        (
          session_id,
          final_failure_count,
          iterations,
          result,
          json.dumps(failing_check_names),
          unsupported_reason,
        ),
      )


def fetch_session(connection: sqlite3.Connection, session_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM quality_check_sessions WHERE session_id = ?",
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
    routed_skill=row["routed_skill"],
    detected_stack=row["detected_stack"],
    scope_type=row["scope_type"],
    initial_failure_count=row["initial_failure_count"],
    level=level,
  )


def build_started_payload_from_fields(
  *,
  session_id: str,
  routed_skill: str,
  detected_stack: str,
  scope_type: str,
  initial_failure_count: int,
  level: str,
) -> dict[str, object]:
  del level  # started payload is identical across levels today
  return {
    "session_id": session_id,
    "routed_skill": routed_skill,
    "detected_stack": detected_stack,
    "scope_type": scope_type,
    "initial_failure_count": initial_failure_count,
  }


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

  failing_check_names = json.loads(row["failing_check_names"] or "[]")

  payload.update({
    "final_failure_count": row["final_failure_count"] or 0,
    "iterations": row["iterations"] or 0,
    "result": row["result"] or "skipped",
    "duration_seconds": duration_seconds,
  })

  if level == "full":
    payload["failing_check_names"] = failing_check_names
    payload["unsupported_reason"] = row["unsupported_reason"] or ""

  return payload


def build_finished_payload_from_fields(
  *,
  session_id: str,
  routed_skill: str,
  detected_stack: str,
  scope_type: str,
  initial_failure_count: int,
  final_failure_count: int,
  iterations: int,
  result: str,
  failing_check_names: list[str],
  unsupported_reason: str,
  duration_seconds: int,
  level: str,
) -> dict[str, object]:
  payload = build_started_payload_from_fields(
    session_id=session_id,
    routed_skill=routed_skill,
    detected_stack=detected_stack,
    scope_type=scope_type,
    initial_failure_count=initial_failure_count,
    level=level,
  )
  payload.update({
    "final_failure_count": final_failure_count,
    "iterations": iterations,
    "result": result,
    "duration_seconds": duration_seconds,
  })
  if level == "full":
    payload["failing_check_names"] = list(failing_check_names)
    payload["unsupported_reason"] = unsupported_reason
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
      event_name=EVENT_QUALITY_CHECK_STARTED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE quality_check_sessions
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
      event_name=EVENT_QUALITY_CHECK_FINISHED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE quality_check_sessions
        SET finished_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )
