"""Telemetry domain helpers for the new-skill scaffolder (SKILL-15).

Shape mirrors :mod:`skill_bill.quality_check` so the scaffolder's lifecycle
(started / finished) stays consistent with every other first-party ceremony.
The MCP tool, the CLI, and the scaffolded-skill subprocess all share the same
payload builders from this module so the wire shape cannot drift between
callers.
"""

from __future__ import annotations

import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  EVENT_NEW_SKILL_SCAFFOLD_FINISHED,
  EVENT_NEW_SKILL_SCAFFOLD_STARTED,
  NEW_SKILL_SCAFFOLD_SESSION_PREFIX,
)
from skill_bill.scaffold import SUPPORTED_SKILL_KINDS
from skill_bill.stats import enqueue_telemetry_event


def generate_new_skill_session_id() -> str:
  """Return a timestamped, randomized session id for a scaffold run.

  Mirrors :func:`skill_bill.quality_check.generate_quality_check_session_id`.
  """
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{NEW_SKILL_SCAFFOLD_SESSION_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_scaffold_params(*, kind: str) -> str | None:
  """Return a validation-error message or ``None`` when the params are OK.

  Only the subset of fields that the ceremony surfaces in telemetry is
  validated here. Schema validation for the full payload lives in
  :mod:`skill_bill.scaffold`.
  """
  if kind not in SUPPORTED_SKILL_KINDS:
    return (
      f"kind '{kind}' is not one of the supported skill kinds "
      f"{sorted(SUPPORTED_SKILL_KINDS)}."
    )
  return None


def save_scaffold_record(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  kind: str,
  skill_name: str,
  platform: str,
  family: str,
  area: str,
  result: str,
) -> None:
  """Persist a scaffold record for later correlation.

  The scaffolder writes into a dedicated ``new_skill_scaffold_sessions``
  table when one is available. We create the table lazily the first time
  we write to it so the module stays side-effect free on import and so a
  read-only DB path stays readable from unrelated CLI commands.
  """
  with connection:
    connection.execute(
      """
      CREATE TABLE IF NOT EXISTS new_skill_scaffold_sessions (
        session_id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        skill_name TEXT NOT NULL,
        platform TEXT NOT NULL DEFAULT '',
        family TEXT NOT NULL DEFAULT '',
        area TEXT NOT NULL DEFAULT '',
        result TEXT NOT NULL,
        recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
      """
    )
    connection.execute(
      """
      INSERT OR REPLACE INTO new_skill_scaffold_sessions (
        session_id, kind, skill_name, platform, family, area, result
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      """,
      (session_id, kind, skill_name, platform, family, area, result),
    )


def build_started_payload_from_fields(
  *,
  session_id: str,
  kind: str,
  skill_name: str,
  platform: str,
  family: str,
  area: str,
  level: str,
) -> dict[str, object]:
  """Return the telemetry payload for the started event.

  Levels: ``anonymous`` is the default, ``full`` includes the skill name,
  platform, family, and area. ``off`` never reaches this path because
  ``enqueue_telemetry_event`` short-circuits when telemetry is disabled.
  """
  payload: dict[str, object] = {
    "session_id": session_id,
    "kind": kind,
  }
  if level == "full":
    payload.update(
      {
        "skill_name": skill_name,
        "platform": platform,
        "family": family,
        "area": area,
      }
    )
  return payload


def build_finished_payload_from_fields(
  *,
  session_id: str,
  kind: str,
  skill_name: str,
  platform: str,
  family: str,
  area: str,
  result: str,
  duration_seconds: int,
  level: str,
) -> dict[str, object]:
  """Return the telemetry payload for the finished event."""
  payload = build_started_payload_from_fields(
    session_id=session_id,
    kind=kind,
    skill_name=skill_name,
    platform=platform,
    family=family,
    area=area,
    level=level,
  )
  payload.update(
    {
      "result": result,
      "duration_seconds": duration_seconds,
    }
  )
  return payload


def emit_new_skill_scaffold_started(
  connection: sqlite3.Connection,
  *,
  payload: dict[str, object],
  enabled: bool,
) -> None:
  """Enqueue the ``skillbill_new_skill_scaffold_started`` event."""
  with connection:
    enqueue_telemetry_event(
      connection,
      event_name=EVENT_NEW_SKILL_SCAFFOLD_STARTED,
      payload=payload,
      enabled=enabled,
    )


def emit_new_skill_scaffold_finished(
  connection: sqlite3.Connection,
  *,
  payload: dict[str, object],
  enabled: bool,
) -> None:
  """Enqueue the ``skillbill_new_skill_scaffold_finished`` event."""
  with connection:
    enqueue_telemetry_event(
      connection,
      event_name=EVENT_NEW_SKILL_SCAFFOLD_FINISHED,
      payload=payload,
      enabled=enabled,
    )


__all__ = [
  "build_finished_payload_from_fields",
  "build_started_payload_from_fields",
  "emit_new_skill_scaffold_finished",
  "emit_new_skill_scaffold_started",
  "generate_new_skill_session_id",
  "save_scaffold_record",
  "validate_scaffold_params",
]
