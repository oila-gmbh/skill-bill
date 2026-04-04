from __future__ import annotations

import json

from skill_bill.constants import LEARNING_SCOPE_PRECEDENCE, TriageDecision


def emit(payload: dict[str, object], output_format: str) -> None:
  if output_format == "json":
    print(json.dumps(payload, indent=2, sort_keys=True))
    return

  for key, value in payload.items():
    if value is None:
      continue
    if isinstance(value, (list, dict)):
      print(f"{key}:")
      print(json.dumps(value, indent=2, sort_keys=True))
      continue
    print(f"{key}: {value}")


def print_numbered_findings(review_run_id: str, numbered_findings: list[dict[str, object]]) -> None:
  print(f"review_run_id: {review_run_id}")
  for finding in numbered_findings:
    print(
      f"{finding['number']}. [{finding['finding_id']}] "
      f"{finding['severity']} | {finding['confidence']} | "
      f"{finding['location']} | {finding['description']}"
    )


def print_triage_result(review_run_id: str, decisions: list[TriageDecision]) -> None:
  print(f"review_run_id: {review_run_id}")
  for decision in decisions:
    line = f"{decision.number}. {decision.finding_id} -> {decision.outcome_type}"
    if decision.note:
      line += f" | note: {decision.note}"
    print(line)


def print_learnings(entries: list[dict[str, object]]) -> None:
  if not entries:
    print("No learnings found.")
    return

  for entry in entries:
    scope_label = entry["scope"]
    scope_key = entry["scope_key"]
    if scope_key:
      scope_label = f"{scope_label}:{scope_key}"
    print(f"{entry['reference']}. [{entry['status']}] {scope_label} | {entry['title']}")


def summarize_applied_learnings(entries: list[dict[str, object]]) -> str:
  if not entries:
    return "none"
  return ", ".join(str(entry["reference"]) for entry in entries)


def print_resolved_learnings(
  *,
  repo_scope_key: str | None,
  skill_name: str | None,
  entries: list[dict[str, object]],
) -> None:
  print(f"scope_precedence: {' > '.join(LEARNING_SCOPE_PRECEDENCE)}")
  if repo_scope_key is not None:
    print(f"repo_scope_key: {repo_scope_key}")
  if skill_name is not None:
    print(f"skill_name: {skill_name}")
  print(f"applied_learnings: {summarize_applied_learnings(entries)}")
  if not entries:
    print("No active learnings matched this review context.")
    return

  for entry in entries:
    scope_label = entry["scope"]
    scope_key = entry["scope_key"]
    if scope_key:
      scope_label = f"{scope_label}:{scope_key}"
    print(f"- [{entry['reference']}] {scope_label} | {entry['title']} | {entry['rule_text']}")
