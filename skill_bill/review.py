from __future__ import annotations

from pathlib import Path
import sqlite3
import sys

from skill_bill.config import telemetry_is_enabled
from skill_bill.constants import (
  FINDING_PATTERN,
  REVIEW_RUN_ID_PATTERN,
  REVIEW_SESSION_ID_PATTERN,
  SUMMARY_PATTERNS,
  SPECIALIST_REVIEWS_PATTERN,
  ImportedFinding,
  ImportedReview,
)
from skill_bill.stats import (
  clear_review_finished_telemetry_state,
  update_review_finished_telemetry_state,
)


def parse_review(text: str) -> ImportedReview:
  review_run_match = REVIEW_RUN_ID_PATTERN.search(text)
  if not review_run_match:
    raise ValueError("Review output is missing 'Review run ID: <review-run-id>'.")
  review_run_id = review_run_match.group("value")
  review_session_match = REVIEW_SESSION_ID_PATTERN.search(text)
  if not review_session_match:
    raise ValueError("Review output is missing 'Review session ID: <review-session-id>'.")
  review_session_id = review_session_match.group("value")

  findings: list[ImportedFinding] = []
  seen_ids: set[str] = set()
  for match in FINDING_PATTERN.finditer(text):
    finding_id = match.group("finding_id")
    if finding_id in seen_ids:
      raise ValueError(f"Review output contains duplicate finding id '{finding_id}'.")
    seen_ids.add(finding_id)
    findings.append(
      ImportedFinding(
        finding_id=finding_id,
        severity=match.group("severity"),
        confidence=match.group("confidence"),
        location=match.group("location").strip(),
        description=match.group("description").strip(),
        finding_text=match.group(0).strip(),
      )
    )

  return ImportedReview(
    review_run_id=review_run_id,
    review_session_id=review_session_id,
    raw_text=text,
    routed_skill=extract_summary_value(text, "routed_skill"),
    detected_scope=extract_summary_value(text, "detected_scope"),
    detected_stack=extract_summary_value(text, "detected_stack"),
    execution_mode=extract_summary_value(text, "execution_mode"),
    specialist_reviews=extract_specialist_reviews(text),
    findings=tuple(findings),
  )


def extract_summary_value(text: str, key: str) -> str | None:
  match = SUMMARY_PATTERNS[key].search(text)
  if not match:
    return None
  return match.group("value").strip()


def extract_specialist_reviews(text: str) -> tuple[str, ...]:
  seen: list[str] = []
  for match in SPECIALIST_REVIEWS_PATTERN.finditer(text):
    for name in match.group("value").split(","):
      stripped = name.strip()
      if stripped and stripped not in seen:
        seen.append(stripped)
  return tuple(seen)


def read_input(input_path: str) -> tuple[str, str | None]:
  if input_path == "-":
    return (sys.stdin.read(), None)
  path = Path(input_path).expanduser().resolve()
  return (path.read_text(encoding="utf-8"), str(path))


def save_imported_review(
  connection: sqlite3.Connection,
  review: ImportedReview,
  *,
  source_path: str | None,
) -> None:
  telemetry_enabled = telemetry_is_enabled()
  existing_review_summary = connection.execute(
    """
    SELECT review_session_id, routed_skill, detected_scope, detected_stack, execution_mode, specialist_reviews
    FROM review_runs
    WHERE review_run_id = ?
    """,
    (review.review_run_id,),
  ).fetchone()
  existing_findings = fetch_imported_findings(connection, review.review_run_id)
  summary_snapshot_changed = (
    existing_review_summary is None
    or existing_review_summary["review_session_id"] != review.review_session_id
    or existing_review_summary["routed_skill"] != review.routed_skill
    or existing_review_summary["detected_scope"] != review.detected_scope
    or existing_review_summary["detected_stack"] != review.detected_stack
    or existing_review_summary["execution_mode"] != review.execution_mode
    or existing_review_summary["specialist_reviews"] != ",".join(review.specialist_reviews)
    or existing_findings != review.findings
  )
  with connection:
    connection.execute(
      """
      INSERT INTO review_runs (
        review_run_id,
        review_session_id,
        routed_skill,
        detected_scope,
        detected_stack,
        execution_mode,
        specialist_reviews,
        source_path,
        raw_text
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(review_run_id) DO UPDATE SET
        review_session_id = excluded.review_session_id,
        routed_skill = excluded.routed_skill,
        detected_scope = excluded.detected_scope,
        detected_stack = excluded.detected_stack,
        execution_mode = excluded.execution_mode,
        specialist_reviews = excluded.specialist_reviews,
        source_path = excluded.source_path,
        raw_text = excluded.raw_text
      """,
      (
        review.review_run_id,
        review.review_session_id,
        review.routed_skill,
        review.detected_scope,
        review.detected_stack,
        review.execution_mode,
        ",".join(review.specialist_reviews),
        source_path,
        review.raw_text,
      ),
    )

    if summary_snapshot_changed:
      clear_review_finished_telemetry_state(connection, review.review_run_id)

    if existing_findings != review.findings:
      connection.execute(
        "DELETE FROM findings WHERE review_run_id = ?",
        (review.review_run_id,),
      )
      for finding in review.findings:
        connection.execute(
          """
          INSERT INTO findings (
            review_run_id,
            finding_id,
            severity,
            confidence,
            location,
            description,
            finding_text
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          (
            review.review_run_id,
            finding.finding_id,
            finding.severity,
            finding.confidence,
            finding.location,
            finding.description,
            finding.finding_text,
          ),
        )

    update_review_finished_telemetry_state(
      connection,
      review_run_id=review.review_run_id,
      enabled=telemetry_enabled,
    )


def fetch_imported_findings(
  connection: sqlite3.Connection,
  review_run_id: str,
) -> tuple[ImportedFinding, ...]:
  rows = connection.execute(
    """
    SELECT finding_id, severity, confidence, location, description, finding_text
    FROM findings
    WHERE review_run_id = ?
    ORDER BY finding_id
    """,
    (review_run_id,),
  ).fetchall()
  return tuple(
    ImportedFinding(
      finding_id=str(row["finding_id"]),
      severity=str(row["severity"]),
      confidence=str(row["confidence"]),
      location=str(row["location"]),
      description=str(row["description"]),
      finding_text=str(row["finding_text"]),
    )
    for row in rows
  )


def fetch_review_summary(connection: sqlite3.Connection, review_run_id: str) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      specialist_reviews,
      review_finished_at,
      review_finished_event_emitted_at
    FROM review_runs
    WHERE review_run_id = ?
    """,
    (review_run_id,),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown review run id '{review_run_id}'.")
  return row


def fetch_finding_metadata(connection: sqlite3.Connection, review_run_id: str, finding_id: str) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT finding_id, severity, confidence
    FROM findings
    WHERE review_run_id = ? AND finding_id = ?
    """,
    (review_run_id, finding_id),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown finding id '{finding_id}' for review run '{review_run_id}'.")
  return row


def fetch_numbered_findings(connection: sqlite3.Connection, review_run_id: str) -> list[dict[str, object]]:
  if not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'.")

  rows = connection.execute(
    """
    SELECT finding_id, severity, confidence, location, description
    FROM findings
    WHERE review_run_id = ?
    ORDER BY finding_id
    """,
    (review_run_id,),
  ).fetchall()

  numbered_findings: list[dict[str, object]] = []
  for index, row in enumerate(rows, start=1):
    numbered_findings.append(
      {
        "number": index,
        "finding_id": row["finding_id"],
        "severity": row["severity"],
        "confidence": row["confidence"],
        "location": row["location"],
        "description": row["description"],
      }
    )
  return numbered_findings


def review_exists(connection: sqlite3.Connection, review_run_id: str) -> bool:
  from skill_bill.db import review_exists as _review_exists
  return _review_exists(connection, review_run_id)
