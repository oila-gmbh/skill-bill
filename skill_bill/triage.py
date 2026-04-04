from __future__ import annotations

import sqlite3

from skill_bill.config import telemetry_is_enabled
from skill_bill.constants import (
  BULK_TRIAGE_PATTERN,
  MEANINGFUL_NOTE_PATTERN,
  TRIAGE_DECISION_PATTERN,
  TriageDecision,
)
from skill_bill.db import finding_exists, review_exists
from skill_bill.stats import update_review_finished_telemetry_state


def expand_bulk_decisions(
  raw_decisions: list[str],
  numbered_findings: list[dict[str, object]],
) -> list[str]:
  expanded: list[str] = []
  for raw_decision in raw_decisions:
    bulk_match = BULK_TRIAGE_PATTERN.fullmatch(raw_decision.strip())
    if bulk_match:
      action = bulk_match.group("action")
      note = bulk_match.group("note") or ""
      for entry in numbered_findings:
        suffix = f" - {note}" if note.strip() else ""
        expanded.append(f"{entry['number']} {action}{suffix}")
    else:
      expanded.append(raw_decision)
  return expanded


def parse_triage_decisions(
  raw_decisions: list[str],
  numbered_findings: list[dict[str, object]],
) -> list[TriageDecision]:
  expanded_decisions = expand_bulk_decisions(raw_decisions, numbered_findings)
  number_to_finding = {
    int(entry["number"]): str(entry["finding_id"])
    for entry in numbered_findings
  }
  decisions: list[TriageDecision] = []
  seen_numbers: set[int] = set()

  for raw_decision in expanded_decisions:
    match = TRIAGE_DECISION_PATTERN.fullmatch(raw_decision.strip())
    if not match:
      raise ValueError(
        "Invalid triage decision format. Use entries like '1 fix', "
        "'2 skip - intentional', 'all fix', or 'all skip - reason'."
      )

    number = int(match.group("number"))
    if number not in number_to_finding:
      raise ValueError(f"Unknown finding number '{number}' for the current review run.")
    if number in seen_numbers:
      raise ValueError(f"Duplicate triage decision for finding number '{number}'.")
    seen_numbers.add(number)

    decisions.append(
      TriageDecision(
        number=number,
        finding_id=number_to_finding[number],
        outcome_type=normalize_triage_action(match.group("action")),
        note=normalize_triage_note(match.group("note")),
      )
    )

  return decisions


def normalize_triage_action(raw_action: str) -> str:
  action = raw_action.strip().lower()
  if action in ("false positive", "false-positive", "false_positive"):
    return "false_positive"
  if action == "fix":
    return "fix_applied"
  if action in ("accept", "accepted"):
    return "finding_accepted"
  if action in ("edit", "edited"):
    return "finding_edited"
  if action in ("dismiss", "skip", "reject"):
    return "fix_rejected"
  raise ValueError(f"Unsupported triage action '{raw_action}'.")


def normalize_triage_note(raw_note: str | None) -> str:
  note = (raw_note or "").strip()
  if note and not MEANINGFUL_NOTE_PATTERN.search(note):
    return ""
  return note


def record_feedback(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  finding_ids: list[str],
  event_type: str,
  note: str,
) -> None:
  if not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'. Import the review first.")

  missing_findings = [
    finding_id
    for finding_id in finding_ids
    if not finding_exists(connection, review_run_id, finding_id)
  ]
  if missing_findings:
    raise ValueError(
      "Unknown finding ids for review run "
      f"'{review_run_id}': {', '.join(sorted(missing_findings))}"
    )

  telemetry_enabled = telemetry_is_enabled()
  with connection:
    for finding_id in finding_ids:
      connection.execute(
        """
        INSERT INTO feedback_events (review_run_id, finding_id, event_type, note)
        VALUES (?, ?, ?, ?)
        """,
        (review_run_id, finding_id, event_type, note),
      )

    update_review_finished_telemetry_state(
      connection,
      review_run_id=review_run_id,
      enabled=telemetry_enabled,
    )
