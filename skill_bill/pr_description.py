from __future__ import annotations

import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  EVENT_PR_DESCRIPTION_GENERATED,
  PR_DESCRIPTION_SESSION_PREFIX,
)
from skill_bill.stats import enqueue_telemetry_event


def generate_pr_description_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{PR_DESCRIPTION_SESSION_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def build_payload(
  *,
  session_id: str,
  commit_count: int,
  files_changed_count: int,
  was_edited_by_user: bool,
  pr_created: bool,
  pr_title: str,
  level: str,
) -> dict[str, object]:
  payload: dict[str, object] = {
    "session_id": session_id,
    "commit_count": commit_count,
    "files_changed_count": files_changed_count,
    "was_edited_by_user": was_edited_by_user,
    "pr_created": pr_created,
  }
  if level == "full":
    payload["pr_title"] = pr_title
  return payload


def emit_event(
  connection: sqlite3.Connection,
  *,
  payload: dict[str, object],
  enabled: bool,
) -> None:
  with connection:
    enqueue_telemetry_event(
      connection,
      event_name=EVENT_PR_DESCRIPTION_GENERATED,
      payload=payload,
      enabled=enabled,
    )
