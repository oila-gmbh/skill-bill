from __future__ import annotations

from datetime import datetime
import json
import sqlite3

from skill_bill.config import load_telemetry_settings, telemetry_is_enabled
from skill_bill.constants import (
  ACCEPTED_FINDING_OUTCOME_TYPES,
  AUDIT_RESULTS,
  BOUNDARY_HISTORY_VALUES,
  COMPLETION_STATUSES,
  FEATURE_FLAG_PATTERNS,
  FEATURE_SIZES,
  FEATURE_VERIFY_COMPLETION_STATUSES,
  FINDING_OUTCOME_TYPES,
  HISTORY_SIGNAL_VALUES,
  REJECTED_FINDING_OUTCOME_TYPES,
  VALIDATION_RESULTS,
)
from skill_bill.db import review_exists


def stats_payload(connection: sqlite3.Connection, review_run_id: str | None) -> dict[str, object]:
  if review_run_id and not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'.")

  finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  payload = summarize_finding_rows(finding_rows)
  payload["review_run_id"] = review_run_id
  return payload


def _rate(count: int, total: int) -> float:
  return round(count / total, 3) if total else 0.0


def _average(values: list[int]) -> float:
  return round(sum(values) / len(values), 2) if values else 0.0


def _parse_json_list(raw_value: object) -> list[object]:
  if not raw_value:
    return []
  try:
    decoded = json.loads(str(raw_value))
  except json.JSONDecodeError:
    return []
  return decoded if isinstance(decoded, list) else []


def _duration_seconds(row: sqlite3.Row) -> int:
  started_at = str(row["started_at"] or "")
  finished_at = str(row["finished_at"] or "")
  if not started_at or not finished_at:
    return 0
  try:
    start_dt = datetime.fromisoformat(started_at)
    end_dt = datetime.fromisoformat(finished_at)
  except (ValueError, TypeError):
    return 0
  return max(0, int((end_dt - start_dt).total_seconds()))


def _count_values(
  rows: list[sqlite3.Row],
  column_name: str,
  expected_values: tuple[str, ...],
) -> dict[str, int]:
  counts = {value: 0 for value in expected_values}
  for row in rows:
    raw_value = str(row[column_name] or "")
    if raw_value in counts:
      counts[raw_value] += 1
  return counts


def feature_verify_stats_payload(connection: sqlite3.Connection) -> dict[str, object]:
  rows = connection.execute(
    """
    SELECT *
    FROM feature_verify_sessions
    ORDER BY started_at, session_id
    """
  ).fetchall()
  finished_rows = [row for row in rows if row["finished_at"]]

  rollout_relevant_runs = sum(1 for row in rows if bool(row["rollout_relevant"]))
  audit_performed_runs = sum(1 for row in finished_rows if bool(row["feature_flag_audit_performed"]))
  history_read_runs = sum(
    1
    for row in finished_rows
    if str(row["history_relevance"] or "none") != "none"
    or str(row["history_helpfulness"] or "none") != "none"
  )
  history_relevant_runs = sum(
    1 for row in finished_rows if str(row["history_relevance"] or "none") in ("medium", "high")
  )
  history_helpful_runs = sum(
    1 for row in finished_rows if str(row["history_helpfulness"] or "none") in ("medium", "high")
  )
  runs_with_gaps_found = sum(
    1 for row in finished_rows if len(_parse_json_list(row["gaps_found"])) > 0
  )
  review_iterations = [
    int(row["review_iterations"])
    for row in finished_rows
    if row["review_iterations"] is not None
  ]
  durations = [
    duration
    for row in finished_rows
    for duration in [_duration_seconds(row)]
    if duration > 0
  ]
  acceptance_criteria_counts = [
    int(row["acceptance_criteria_count"])
    for row in rows
    if row["acceptance_criteria_count"] is not None
  ]

  return {
    "workflow": "bill-feature-verify",
    "total_runs": len(rows),
    "finished_runs": len(finished_rows),
    "in_progress_runs": len(rows) - len(finished_rows),
    "completion_status_counts": _count_values(
      finished_rows,
      "completion_status",
      FEATURE_VERIFY_COMPLETION_STATUSES,
    ),
    "audit_result_counts": _count_values(
      finished_rows,
      "audit_result",
      AUDIT_RESULTS,
    ),
    "rollout_relevant_runs": rollout_relevant_runs,
    "rollout_relevant_rate": _rate(rollout_relevant_runs, len(rows)),
    "feature_flag_audit_performed_runs": audit_performed_runs,
    "feature_flag_audit_performed_rate": _rate(audit_performed_runs, len(finished_rows)),
    "history_read_runs": history_read_runs,
    "history_read_rate": _rate(history_read_runs, len(finished_rows)),
    "history_relevant_runs": history_relevant_runs,
    "history_relevant_rate": _rate(history_relevant_runs, len(finished_rows)),
    "history_helpful_runs": history_helpful_runs,
    "history_helpful_rate": _rate(history_helpful_runs, len(finished_rows)),
    "history_relevance_counts": _count_values(
      finished_rows,
      "history_relevance",
      HISTORY_SIGNAL_VALUES,
    ),
    "history_helpfulness_counts": _count_values(
      finished_rows,
      "history_helpfulness",
      HISTORY_SIGNAL_VALUES,
    ),
    "runs_with_gaps_found": runs_with_gaps_found,
    "average_acceptance_criteria_count": _average(acceptance_criteria_counts),
    "average_review_iterations": _average(review_iterations),
    "average_duration_seconds": _average(durations),
  }


def feature_implement_stats_payload(connection: sqlite3.Connection) -> dict[str, object]:
  rows = connection.execute(
    """
    SELECT *
    FROM feature_implement_sessions
    ORDER BY started_at, session_id
    """
  ).fetchall()
  finished_rows = [row for row in rows if row["finished_at"]]

  rollout_needed_runs = sum(1 for row in rows if bool(row["rollout_needed"]))
  feature_flag_used_runs = sum(1 for row in finished_rows if bool(row["feature_flag_used"]))
  pr_created_runs = sum(1 for row in finished_rows if bool(row["pr_created"]))
  boundary_history_written_runs = sum(
    1 for row in finished_rows if bool(row["boundary_history_written"])
  )

  acceptance_criteria_counts = [
    int(row["acceptance_criteria_count"])
    for row in rows
    if row["acceptance_criteria_count"] is not None
  ]
  spec_word_counts = [
    int(row["spec_word_count"])
    for row in rows
    if row["spec_word_count"] is not None
  ]
  review_iterations = [
    int(row["review_iterations"])
    for row in finished_rows
    if row["review_iterations"] is not None
  ]
  audit_iterations = [
    int(row["audit_iterations"])
    for row in finished_rows
    if row["audit_iterations"] is not None
  ]
  files_created = [
    int(row["files_created"])
    for row in finished_rows
    if row["files_created"] is not None
  ]
  files_modified = [
    int(row["files_modified"])
    for row in finished_rows
    if row["files_modified"] is not None
  ]
  tasks_completed = [
    int(row["tasks_completed"])
    for row in finished_rows
    if row["tasks_completed"] is not None
  ]
  durations = [
    duration
    for row in finished_rows
    for duration in [_duration_seconds(row)]
    if duration > 0
  ]

  return {
    "workflow": "bill-feature-implement",
    "total_runs": len(rows),
    "finished_runs": len(finished_rows),
    "in_progress_runs": len(rows) - len(finished_rows),
    "feature_size_counts": _count_values(
      rows,
      "feature_size",
      FEATURE_SIZES,
    ),
    "completion_status_counts": _count_values(
      finished_rows,
      "completion_status",
      COMPLETION_STATUSES,
    ),
    "audit_result_counts": _count_values(
      finished_rows,
      "audit_result",
      AUDIT_RESULTS,
    ),
    "validation_result_counts": _count_values(
      finished_rows,
      "validation_result",
      VALIDATION_RESULTS,
    ),
    "feature_flag_pattern_counts": _count_values(
      finished_rows,
      "feature_flag_pattern",
      FEATURE_FLAG_PATTERNS,
    ),
    "boundary_history_value_counts": _count_values(
      finished_rows,
      "boundary_history_value",
      BOUNDARY_HISTORY_VALUES,
    ),
    "rollout_needed_runs": rollout_needed_runs,
    "rollout_needed_rate": _rate(rollout_needed_runs, len(rows)),
    "feature_flag_used_runs": feature_flag_used_runs,
    "feature_flag_used_rate": _rate(feature_flag_used_runs, len(finished_rows)),
    "pr_created_runs": pr_created_runs,
    "pr_created_rate": _rate(pr_created_runs, len(finished_rows)),
    "boundary_history_written_runs": boundary_history_written_runs,
    "boundary_history_written_rate": _rate(
      boundary_history_written_runs,
      len(finished_rows),
    ),
    "average_acceptance_criteria_count": _average(acceptance_criteria_counts),
    "average_spec_word_count": _average(spec_word_counts),
    "average_review_iterations": _average(review_iterations),
    "average_audit_iterations": _average(audit_iterations),
    "average_files_created": _average(files_created),
    "average_files_modified": _average(files_modified),
    "average_tasks_completed": _average(tasks_completed),
    "average_duration_seconds": _average(durations),
  }


def count_rows(
  connection: sqlite3.Connection,
  base_query: str,
  *,
  review_run_id: str | None = None,
) -> int:
  query = base_query
  parameters: list[str] = []
  if review_run_id:
    query += " WHERE review_run_id = ?"
    parameters.append(review_run_id)
  row = connection.execute(query, tuple(parameters)).fetchone()
  if row is None:
    return 0
  return int(row[0])


def empty_severity_counts() -> dict[str, int]:
  return {"Blocker": 0, "Major": 0, "Minor": 0}


def latest_finding_outcomes(
  connection: sqlite3.Connection,
  *,
  review_run_id: str | None = None,
) -> list[sqlite3.Row]:
  parameters: list[str] = []
  latest_feedback_filter = ""
  findings_filter = ""
  if review_run_id:
    latest_feedback_filter = "WHERE review_run_id = ?"
    findings_filter = "WHERE f.review_run_id = ?"
    parameters.append(review_run_id)
    parameters.append(review_run_id)

  return connection.execute(
    f"""
    WITH latest_feedback AS (
      SELECT review_run_id, finding_id, MAX(id) AS latest_id
      FROM feedback_events
      {latest_feedback_filter}
      GROUP BY review_run_id, finding_id
    )
    SELECT
      f.review_run_id,
      f.finding_id,
      f.severity,
      f.confidence,
      f.location,
      f.description,
      COALESCE(fe.event_type, '') AS outcome_type,
      COALESCE(fe.note, '') AS note
    FROM findings f
    LEFT JOIN latest_feedback lf
      ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
    LEFT JOIN feedback_events fe
      ON fe.id = lf.latest_id
    {findings_filter}
    ORDER BY f.review_run_id, f.finding_id
    """,
    tuple(parameters),
  ).fetchall()


def summarize_finding_rows(finding_rows: list[sqlite3.Row]) -> dict[str, object]:
  outcome_counts = {outcome_type: 0 for outcome_type in FINDING_OUTCOME_TYPES}
  accepted_severity_counts = empty_severity_counts()
  rejected_severity_counts = empty_severity_counts()
  unresolved_severity_counts = empty_severity_counts()
  accepted_finding_details: list[dict[str, object]] = []
  rejected_findings: list[dict[str, object]] = []
  accepted_findings = 0
  rejected_findings_count = 0
  unresolved_findings = 0
  rejected_findings_with_notes = 0

  for row in finding_rows:
    severity = str(row["severity"])
    outcome_type = str(row["outcome_type"] or "")
    note = str(row["note"] or "")
    if outcome_type in FINDING_OUTCOME_TYPES:
      outcome_counts[outcome_type] += 1

    if outcome_type in ACCEPTED_FINDING_OUTCOME_TYPES:
      accepted_findings += 1
      accepted_severity_counts[severity] += 1
      accepted_finding_details.append(
        {
          "finding_id": row["finding_id"],
          "severity": severity,
          "confidence": row["confidence"],
          "location": row["location"],
          "description": row["description"],
          "outcome_type": outcome_type,
        }
      )
      continue

    if outcome_type in REJECTED_FINDING_OUTCOME_TYPES:
      rejected_findings_count += 1
      rejected_severity_counts[severity] += 1
      rejected_payload: dict[str, object] = {
        "finding_id": row["finding_id"],
        "severity": severity,
        "confidence": row["confidence"],
        "location": row["location"],
        "description": row["description"],
        "outcome_type": outcome_type,
      }
      if note:
        rejected_payload["note"] = note
        rejected_findings_with_notes += 1
      rejected_findings.append(rejected_payload)
      continue

    unresolved_findings += 1
    unresolved_severity_counts[severity] += 1

  total_findings = len(finding_rows)
  accepted_rate = round(accepted_findings / total_findings, 3) if total_findings else 0.0
  rejected_rate = round(rejected_findings_count / total_findings, 3) if total_findings else 0.0

  return {
    "total_findings": total_findings,
    "accepted_findings": accepted_findings,
    "rejected_findings": rejected_findings_count,
    "unresolved_findings": unresolved_findings,
    "accepted_rate": accepted_rate,
    "rejected_rate": rejected_rate,
    "latest_outcome_counts": outcome_counts,
    "accepted_severity_counts": accepted_severity_counts,
    "rejected_severity_counts": rejected_severity_counts,
    "unresolved_severity_counts": unresolved_severity_counts,
    "accepted_finding_details": accepted_finding_details,
    "rejected_findings_with_notes": rejected_findings_with_notes,
    "rejected_finding_details": rejected_findings,
  }


def clear_review_finished_telemetry_state(
  connection: sqlite3.Connection,
  review_run_id: str,
) -> None:
  connection.execute(
    """
    UPDATE review_runs
    SET review_finished_at = NULL,
        review_finished_event_emitted_at = NULL
    WHERE review_run_id = ?
    """,
    (review_run_id,),
  )


def build_review_finished_payload(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  review_summary: sqlite3.Row | None = None,
  finding_rows: list[sqlite3.Row] | None = None,
  level: str = "anonymous",
) -> dict[str, object]:
  from skill_bill.learnings import fetch_session_learnings
  from skill_bill.review import fetch_review_summary

  if review_summary is None:
    review_summary = fetch_review_summary(connection, review_run_id)
  if finding_rows is None:
    finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  payload = summarize_finding_rows(finding_rows)
  for key in (
    "rejected_findings",
    "rejected_rate",
    "rejected_findings_with_notes",
    "latest_outcome_counts",
    "unresolved_severity_counts",
    "accepted_severity_counts",
    "rejected_severity_counts",
  ):
    payload.pop(key, None)

  if level == "full":
    def enrich_finding_details(details: object) -> list[dict[str, object]]:
      return [
        {
          "finding_id": detail["finding_id"],
          "severity": detail["severity"],
          "confidence": detail["confidence"],
          "outcome_type": detail["outcome_type"],
          "location": detail.get("location", ""),
          "description": detail.get("description", ""),
          **({"note": detail["note"]} if detail.get("note") else {}),
        }
        for detail in details if isinstance(detail, dict)
      ]
    payload["accepted_finding_details"] = enrich_finding_details(payload.get("accepted_finding_details", []))
    payload["rejected_finding_details"] = enrich_finding_details(payload.get("rejected_finding_details", []))
  else:
    def redact_finding_details(details: object) -> list[dict[str, object]]:
      return [
        {
          "finding_id": detail["finding_id"],
          "severity": detail["severity"],
          "confidence": detail["confidence"],
          "outcome_type": detail["outcome_type"],
        }
        for detail in details if isinstance(detail, dict)
      ]
    payload["accepted_finding_details"] = redact_finding_details(payload.get("accepted_finding_details", []))
    payload["rejected_finding_details"] = redact_finding_details(payload.get("rejected_finding_details", []))

  specialist_reviews_raw = str(review_summary["specialist_reviews"] or "")
  specialist_reviews = [s.strip() for s in specialist_reviews_raw.split(",") if s.strip()]
  session_id = str(review_summary["review_session_id"] or "")
  raw_scope = str(review_summary["detected_scope"] or "")
  normalized_scope = raw_scope.split("(")[0].strip() if "(" in raw_scope else raw_scope
  learnings_data = fetch_session_learnings(connection, session_id) if session_id else None
  default_scope_counts = {"global": 0, "repo": 0, "skill": 0}

  if level == "full":
    learnings_entries = [
      {
        "reference": entry["reference"],
        "scope": entry["scope"],
        **({"title": entry["title"]} if entry.get("title") else {}),
        **({"rule_text": entry["rule_text"]} if entry.get("rule_text") else {}),
      }
      for entry in (learnings_data.get("learnings", []) if learnings_data else [])
    ]
  else:
    learnings_entries = [
      {"reference": entry["reference"], "scope": entry["scope"]}
      for entry in (learnings_data.get("learnings", []) if learnings_data else [])
    ]

  payload.update(
    {
      "review_run_id": review_run_id,
      "review_session_id": session_id,
      "routed_skill": review_summary["routed_skill"],
      "review_subskills": specialist_reviews,
      "review_scope": normalized_scope,
      "review_platform": review_summary["detected_stack"],
      "execution_mode": review_summary["execution_mode"],
      "review_finished_at": review_summary["review_finished_at"],
      "learnings": {
        "applied_count": learnings_data.get("applied_learning_count", 0) if learnings_data else 0,
        "applied_references": learnings_data.get("applied_learning_references", []) if learnings_data else [],
        "applied_summary": learnings_data.get("applied_learnings", "none") if learnings_data else "none",
        "scope_counts": dict(default_scope_counts | (learnings_data.get("scope_counts") or {}))
        if learnings_data
        else dict(default_scope_counts),
        "entries": learnings_entries,
      },
    }
  )
  return payload


def update_review_finished_telemetry_state(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  enabled: bool | None = None,
  level: str | None = None,
) -> dict[str, object] | None:
  """Drive the review-finished telemetry lifecycle.

  Returns the built finished payload when the review lifecycle is fully
  resolved, so orchestrated callers can embed it in a parent event without
  a local emission. For standalone (non-orchestrated) runs, the payload is
  also enqueued to the outbox as before.
  """
  from skill_bill.review import fetch_review_summary

  if enabled is None or level is None:
    try:
      settings = load_telemetry_settings()
      if enabled is None:
        enabled = settings.enabled
      if level is None:
        level = settings.level
    except ValueError:
      if enabled is None:
        enabled = False
      if level is None:
        level = "off"
  review_summary = fetch_review_summary(connection, review_run_id)

  session_id = str(review_summary["review_session_id"] or "")
  if session_id:
    already_emitted = connection.execute(
      """
      SELECT 1 FROM review_runs
      WHERE review_session_id = ?
        AND review_run_id != ?
        AND review_finished_event_emitted_at IS NOT NULL
      """,
      (session_id, review_run_id),
    ).fetchone()
    if already_emitted:
      return None

  finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  summarized_findings = summarize_finding_rows(finding_rows)
  resolved_findings = int(summarized_findings["accepted_findings"]) + int(summarized_findings["rejected_findings"])

  if int(summarized_findings["total_findings"]) > 0 and resolved_findings == 0:
    if review_summary["review_finished_at"] or review_summary["review_finished_event_emitted_at"]:
      clear_review_finished_telemetry_state(connection, review_run_id)
    return None

  if not review_summary["review_finished_at"]:
    connection.execute(
      """
      UPDATE review_runs
      SET review_finished_at = CURRENT_TIMESTAMP
      WHERE review_run_id = ? AND review_finished_at IS NULL
      """,
      (review_run_id,),
    )
    review_summary = fetch_review_summary(connection, review_run_id)

  payload = build_review_finished_payload(
    connection,
    review_run_id=review_run_id,
    review_summary=review_summary,
    finding_rows=finding_rows,
    level=level,
  )

  orchestrated = False
  try:
    orchestrated = bool(review_summary["orchestrated_run"])
  except (IndexError, KeyError):
    orchestrated = False

  if orchestrated:
    # Parent-owned: never enqueue. The caller retrieves this payload and
    # embeds it in its own finished event.
    return payload

  if review_summary["review_finished_event_emitted_at"]:
    if enabled:
      update_pending_review_finished_event(
        connection,
        review_session_id=str(review_summary["review_session_id"] or ""),
        payload=payload,
      )
    return payload

  enqueue_telemetry_event(
    connection,
    event_name="skillbill_review_finished",
    payload=payload,
    enabled=enabled,
  )
  if enabled:
    connection.execute(
      """
      UPDATE review_runs
      SET review_finished_event_emitted_at = CURRENT_TIMESTAMP
      WHERE review_run_id = ?
      """,
      (review_run_id,),
    )
  return payload


def update_pending_review_finished_event(
  connection: sqlite3.Connection,
  *,
  review_session_id: str,
  payload: dict[str, object],
) -> None:
  connection.execute(
    """
    UPDATE telemetry_outbox
    SET payload_json = ?
    WHERE event_name = 'skillbill_review_finished'
      AND synced_at IS NULL
      AND json_extract(payload_json, '$.review_session_id') = ?
    """,
    (json.dumps(payload, sort_keys=True), review_session_id),
  )


def enqueue_telemetry_event(
  connection: sqlite3.Connection,
  *,
  event_name: str,
  payload: dict[str, object],
  enabled: bool | None = None,
) -> None:
  if enabled is None:
    enabled = telemetry_is_enabled()
  if not enabled:
    return
  connection.execute(
    """
    INSERT INTO telemetry_outbox (event_name, payload_json)
    VALUES (?, ?)
    """,
    (
      event_name,
      json.dumps(payload, sort_keys=True),
    ),
  )


def pending_telemetry_count(connection: sqlite3.Connection) -> int:
  row = connection.execute(
    "SELECT COUNT(*) FROM telemetry_outbox WHERE synced_at IS NULL"
  ).fetchone()
  if row is None:
    return 0
  return int(row[0])


def latest_telemetry_error(connection: sqlite3.Connection) -> str | None:
  row = connection.execute(
    """
    SELECT last_error
    FROM telemetry_outbox
    WHERE synced_at IS NULL AND last_error != ''
    ORDER BY id DESC
    LIMIT 1
    """
  ).fetchone()
  if row is None:
    return None
  return str(row[0]).strip() or None


def fetch_pending_telemetry_events(
  connection: sqlite3.Connection,
  *,
  limit: int,
) -> list[sqlite3.Row]:
  return connection.execute(
    """
    SELECT id, event_name, payload_json, created_at
    FROM telemetry_outbox
    WHERE synced_at IS NULL
    ORDER BY id
    LIMIT ?
    """,
    (limit,),
  ).fetchall()


def mark_telemetry_synced(connection: sqlite3.Connection, event_ids: list[int]) -> None:
  if not event_ids:
    return
  placeholders = ", ".join("?" for _ in event_ids)
  with connection:
    connection.execute(
      f"""
      UPDATE telemetry_outbox
      SET synced_at = CURRENT_TIMESTAMP,
          last_error = ''
      WHERE id IN ({placeholders})
      """,
      tuple(event_ids),
    )


def mark_telemetry_failed(connection: sqlite3.Connection, *, event_ids: list[int], error_message: str) -> None:
  if not event_ids:
    return
  placeholders = ", ".join("?" for _ in event_ids)
  with connection:
    connection.execute(
      f"""
      UPDATE telemetry_outbox
      SET last_error = ?
      WHERE id IN ({placeholders})
      """,
      tuple([error_message] + event_ids),
    )
