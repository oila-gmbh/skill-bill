from __future__ import annotations

import json
import sqlite3

from skill_bill.constants import (
  LEARNING_SCOPE_PRECEDENCE,
  LEARNING_SCOPES,
  LEARNING_STATUSES,
  REJECTED_FINDING_OUTCOME_TYPES,
)
from skill_bill.db import finding_exists


def validate_learning_scope(scope: str, scope_key: str) -> tuple[str, str]:
  if scope not in LEARNING_SCOPES:
    raise ValueError(f"Learning scope must be one of {', '.join(LEARNING_SCOPES)}.")

  normalized_scope_key = scope_key.strip()
  if scope == "global":
    return (scope, "")
  if not normalized_scope_key:
    raise ValueError(f"Learning scope '{scope}' requires a non-empty --scope-key.")
  return (scope, normalized_scope_key)


def validate_learning_source(
  connection: sqlite3.Connection,
  *,
  source_review_run_id: str | None,
  source_finding_id: str | None,
) -> tuple[str, str, sqlite3.Row]:
  if not source_review_run_id or not source_finding_id:
    raise ValueError(
      "Learnings must be derived from a rejected review finding. "
      "Provide both --from-run and --from-finding."
    )

  if not finding_exists(connection, source_review_run_id, source_finding_id):
    raise ValueError(
      "Unknown learning source "
      f"'{source_review_run_id}:{source_finding_id}'. Import the review and finding first."
    )

  rejected_outcome = fetch_latest_rejected_outcome(
    connection,
    review_run_id=source_review_run_id,
    finding_id=source_finding_id,
  )
  if rejected_outcome is None:
    raise ValueError(
      f"Finding '{source_finding_id}' in run '{source_review_run_id}' has no rejected outcome. "
      "Learnings can only be created from findings the user rejected (fix_rejected or false_positive)."
    )

  return (source_review_run_id, source_finding_id, rejected_outcome)


def fetch_latest_rejected_outcome(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  finding_id: str,
) -> sqlite3.Row | None:
  placeholders = ", ".join("?" for _ in REJECTED_FINDING_OUTCOME_TYPES)
  return connection.execute(
    f"""
    SELECT event_type, note
    FROM feedback_events
    WHERE review_run_id = ? AND finding_id = ? AND event_type IN ({placeholders})
    ORDER BY id DESC
    LIMIT 1
    """,
    (review_run_id, finding_id, *REJECTED_FINDING_OUTCOME_TYPES),
  ).fetchone()


def normalize_optional_lookup_value(raw_value: str | None, argument_name: str) -> str | None:
  if raw_value is None:
    return None
  normalized = raw_value.strip()
  if not normalized:
    raise ValueError(f"{argument_name} must not be empty when provided.")
  return normalized


def add_learning(
  connection: sqlite3.Connection,
  *,
  scope: str,
  scope_key: str,
  title: str,
  rule_text: str,
  rationale: str,
  source_review_run_id: str | None,
  source_finding_id: str | None,
) -> int:
  scope, scope_key = validate_learning_scope(scope, scope_key)
  source_review_run_id, source_finding_id, rejected_outcome = validate_learning_source(
    connection,
    source_review_run_id=source_review_run_id,
    source_finding_id=source_finding_id,
  )

  if not rationale.strip() and str(rejected_outcome["note"] or "").strip():
    rationale = str(rejected_outcome["note"]).strip()

  if not title.strip():
    raise ValueError("Learning title must not be empty.")
  if not rule_text.strip():
    raise ValueError("Learning rule text must not be empty.")

  with connection:
    cursor = connection.execute(
      """
      INSERT INTO learnings (
        scope,
        scope_key,
        title,
        rule_text,
        rationale,
        status,
        source_review_run_id,
        source_finding_id
      ) VALUES (?, ?, ?, ?, ?, 'active', ?, ?)
      """,
      (
        scope,
        scope_key,
        title.strip(),
        rule_text.strip(),
        rationale.strip(),
        source_review_run_id,
        source_finding_id,
      ),
    )
    learning_id = int(cursor.lastrowid)
  return learning_id


def get_learning(connection: sqlite3.Connection, learning_id: int) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
    WHERE id = ?
    """,
    (learning_id,),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown learning id '{learning_id}'.")
  return row


def list_learnings(
  connection: sqlite3.Connection,
  *,
  status: str,
) -> list[sqlite3.Row]:
  parameters: list[str] = []
  query = """
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
  """
  if status != "all":
    query += " WHERE status = ?"
    parameters.append(status)
  query += " ORDER BY id"
  return connection.execute(query, tuple(parameters)).fetchall()


def resolve_learnings(
  connection: sqlite3.Connection,
  *,
  repo_scope_key: str | None,
  skill_name: str | None,
) -> tuple[str | None, str | None, list[sqlite3.Row]]:
  repo_scope_key = normalize_optional_lookup_value(repo_scope_key, "--repo")
  skill_name = normalize_optional_lookup_value(skill_name, "--skill")

  scope_clauses = ["scope = 'global'"]
  parameters: list[str] = []
  if repo_scope_key is not None:
    scope_clauses.append("(scope = 'repo' AND scope_key = ?)")
    parameters.append(repo_scope_key)
  if skill_name is not None:
    scope_clauses.append("(scope = 'skill' AND scope_key = ?)")
    parameters.append(skill_name)

  rows = connection.execute(
    f"""
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
    WHERE status = 'active'
      AND ({' OR '.join(scope_clauses)})
    ORDER BY
      CASE scope
        WHEN 'skill' THEN 0
        WHEN 'repo' THEN 1
        ELSE 2
      END,
      id
    """,
    tuple(parameters),
  ).fetchall()
  return (repo_scope_key, skill_name, list(rows))


def edit_learning(
  connection: sqlite3.Connection,
  *,
  learning_id: int,
  scope: str | None,
  scope_key: str | None,
  title: str | None,
  rule_text: str | None,
  rationale: str | None,
) -> sqlite3.Row:
  current = get_learning(connection, learning_id)

  next_scope = current["scope"] if scope is None else scope
  next_scope_key = current["scope_key"] if scope_key is None else scope_key
  next_scope, next_scope_key = validate_learning_scope(next_scope, next_scope_key)
  next_title = current["title"] if title is None else title.strip()
  next_rule_text = current["rule_text"] if rule_text is None else rule_text.strip()
  next_rationale = current["rationale"] if rationale is None else rationale.strip()

  if not next_title:
    raise ValueError("Learning title must not be empty.")
  if not next_rule_text:
    raise ValueError("Learning rule text must not be empty.")

  with connection:
    connection.execute(
      """
      UPDATE learnings
      SET scope = ?,
          scope_key = ?,
          title = ?,
          rule_text = ?,
          rationale = ?,
          updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """,
      (
        next_scope,
        next_scope_key,
        next_title,
        next_rule_text,
        next_rationale,
        learning_id,
        ),
      )
  return get_learning(connection, learning_id)


def set_learning_status(
  connection: sqlite3.Connection,
  *,
  learning_id: int,
  status: str,
) -> sqlite3.Row:
  if status not in LEARNING_STATUSES:
    raise ValueError(f"Learning status must be one of {', '.join(LEARNING_STATUSES)}.")
  get_learning(connection, learning_id)
  with connection:
    connection.execute(
      """
      UPDATE learnings
      SET status = ?, updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """,
      (status, learning_id),
    )
  return get_learning(connection, learning_id)


def delete_learning(connection: sqlite3.Connection, learning_id: int) -> None:
  get_learning(connection, learning_id)
  with connection:
    connection.execute("DELETE FROM learnings WHERE id = ?", (learning_id,))


def count_learnings(connection: sqlite3.Connection, *, status: str | None = None) -> int:
  query = "SELECT COUNT(*) FROM learnings"
  parameters: list[str] = []
  if status is not None:
    query += " WHERE status = ?"
    parameters.append(status)
  row = connection.execute(query, tuple(parameters)).fetchone()
  if row is None:
    return 0
  return int(row[0])


def learning_reference(learning_id: int) -> str:
  return f"L-{learning_id:03d}"


def learning_payload(row: sqlite3.Row) -> dict[str, object]:
  return {
    "id": row["id"],
    "reference": learning_reference(int(row["id"])),
    "scope": row["scope"],
    "scope_key": row["scope_key"],
    "title": row["title"],
    "rule_text": row["rule_text"],
    "rationale": row["rationale"],
    "status": row["status"],
    "source_review_run_id": row["source_review_run_id"],
    "source_finding_id": row["source_finding_id"],
    "created_at": row["created_at"],
    "updated_at": row["updated_at"],
  }


def learning_summary_payload(entry: dict[str, object]) -> dict[str, object]:
  return {
    "reference": entry["reference"],
    "scope": entry["scope"],
    "title": entry["title"],
    "rule_text": entry["rule_text"],
    "rationale": entry["rationale"],
  }


def scope_counts(entries: list[dict[str, object]]) -> dict[str, int]:
  counts = {scope: 0 for scope in LEARNING_SCOPES}
  for entry in entries:
    scope = str(entry["scope"])
    counts[scope] = counts.get(scope, 0) + 1
  return counts


def save_session_learnings(
  connection: sqlite3.Connection,
  *,
  review_session_id: str,
  learnings_json: str,
) -> None:
  connection.execute(
    """
    INSERT INTO session_learnings (review_session_id, learnings_json, updated_at)
    VALUES (?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(review_session_id) DO UPDATE SET
      learnings_json = excluded.learnings_json,
      updated_at = CURRENT_TIMESTAMP
    """,
    (review_session_id, learnings_json),
  )


def fetch_session_learnings(
  connection: sqlite3.Connection,
  review_session_id: str,
) -> dict[str, object] | None:
  row = connection.execute(
    "SELECT learnings_json FROM session_learnings WHERE review_session_id = ?",
    (review_session_id,),
  ).fetchone()
  if row is None:
    return None
  try:
    return json.loads(str(row[0]))
  except (json.JSONDecodeError, TypeError):
    return None
