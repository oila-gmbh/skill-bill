from __future__ import annotations

import json

from mcp.server.fastmcp import FastMCP

from skill_bill import __version__
from skill_bill.config import load_telemetry_settings, telemetry_is_enabled
from skill_bill.constants import LEARNING_SCOPE_PRECEDENCE
from skill_bill.db import open_db, resolve_db_path
from skill_bill.feature_implement import (
  emit_finished,
  emit_started,
  generate_feature_session_id,
  save_finished,
  save_started,
  validate_finished_params,
  validate_started_params,
)
from skill_bill.learnings import (
  learning_payload,
  learning_summary_payload,
  resolve_learnings as _resolve_learnings,
  save_session_learnings,
  scope_counts,
)
from skill_bill.output import summarize_applied_learnings
from skill_bill.review import (
  fetch_numbered_findings,
  parse_review,
  save_imported_review,
)
from skill_bill.stats import stats_payload, update_review_finished_telemetry_state
from skill_bill.triage import (
  parse_triage_decisions,
  record_feedback,
)

mcp = FastMCP("skill-bill")


@mcp.tool()
def import_review(review_text: str) -> dict:
  """Import code review output into the local telemetry store.

  Pass the complete review output text (Sections 1 through 4) as produced by
  a bill-code-review skill execution. The review must contain a Review run ID
  and Review session ID line. Findings are parsed from the risk register.
  """
  review = parse_review(review_text)
  if not telemetry_is_enabled():
    return {
      "status": "skipped",
      "reason": "telemetry is disabled",
      "review_run_id": review.review_run_id,
      "finding_count": len(review.findings),
    }
  with open_db() as (connection, db_path):
    save_imported_review(connection, review, source_path=None)
    if len(review.findings) == 0:
      update_review_finished_telemetry_state(
        connection,
        review_run_id=review.review_run_id,
      )
  return {
    "db_path": str(db_path),
    "review_run_id": review.review_run_id,
    "review_session_id": review.review_session_id,
    "finding_count": len(review.findings),
    "routed_skill": review.routed_skill,
    "detected_scope": review.detected_scope,
    "detected_stack": review.detected_stack,
    "execution_mode": review.execution_mode,
  }


@mcp.tool()
def triage_findings(review_run_id: str, decisions: list[str]) -> dict:
  """Record triage decisions for findings in an imported review run.

  Each decision is a string like '1 fix', '2 skip - intentional',
  '3 accept', 'all fix', or a structured selection like
  'fix=[1,3] reject=[2]'. The numbers refer to the finding positions
  in the risk register.

  Valid actions: fix, accept, skip, edit, false_positive.
  Append a reason after ' - ' for skip and false_positive decisions.
  """
  if not telemetry_is_enabled():
    return {
      "status": "skipped",
      "reason": "telemetry is disabled",
      "review_run_id": review_run_id,
    }
  with open_db() as (connection, db_path):
    numbered_findings = fetch_numbered_findings(connection, review_run_id)
    parsed_decisions = parse_triage_decisions(decisions, numbered_findings)
    for decision in parsed_decisions:
      record_feedback(
        connection,
        review_run_id=review_run_id,
        finding_ids=[decision.finding_id],
        event_type=decision.outcome_type,
        note=decision.note,
      )
  return {
    "db_path": str(db_path),
    "review_run_id": review_run_id,
    "recorded": [
      {
        "number": d.number,
        "finding_id": d.finding_id,
        "outcome_type": d.outcome_type,
        "note": d.note,
      }
      for d in parsed_decisions
    ],
  }


@mcp.tool()
def resolve_learnings(
  repo: str | None = None,
  skill: str | None = None,
  review_session_id: str | None = None,
) -> dict:
  """Resolve active learnings for a review context.

  Learnings are matched by scope precedence: skill > repo > global.
  Pass repo for repo-scoped learnings and skill for skill-scoped learnings.
  When review_session_id is provided, the resolved learnings are cached
  for inclusion in the review-finished telemetry event.
  """
  if not telemetry_is_enabled():
    return {
      "status": "skipped",
      "reason": "telemetry is disabled",
      "applied_learnings": "none",
      "learnings": [],
    }
  with open_db() as (connection, db_path):
    with connection:
      repo_scope_key, skill_name, rows = _resolve_learnings(
        connection,
        repo_scope_key=repo,
        skill_name=skill,
      )
      payload_entries = [learning_payload(row) for row in rows]
      if review_session_id:
        learnings_cache = {
          "skill_name": skill_name,
          "applied_learning_count": len(payload_entries),
          "applied_learning_references": [entry["reference"] for entry in payload_entries],
          "applied_learnings": summarize_applied_learnings(payload_entries),
          "scope_counts": scope_counts(payload_entries),
          "learnings": [learning_summary_payload(entry) for entry in payload_entries],
        }
        save_session_learnings(
          connection,
          review_session_id=review_session_id,
          learnings_json=json.dumps(learnings_cache, sort_keys=True),
        )
  result = {
    "db_path": str(db_path),
    "repo_scope_key": repo_scope_key,
    "skill_name": skill_name,
    "scope_precedence": list(LEARNING_SCOPE_PRECEDENCE),
    "applied_learnings": summarize_applied_learnings(payload_entries),
    "learnings": payload_entries,
  }
  if review_session_id:
    result["review_session_id"] = review_session_id
  return result


@mcp.tool()
def review_stats(review_run_id: str | None = None) -> dict:
  """Show aggregate or per-run review acceptance metrics.

  When review_run_id is provided, stats are scoped to that single review.
  Otherwise, stats cover all imported reviews.
  """
  with open_db(sync=False) as (connection, db_path):
    payload = stats_payload(connection, review_run_id)
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def doctor() -> dict:
  """Check skill-bill installation health.

  Returns the installed version, database path and existence,
  and telemetry status.
  """
  db_path = resolve_db_path(None)
  try:
    settings = load_telemetry_settings()
    telemetry_enabled = settings.enabled
    telemetry_level = settings.level
  except ValueError:
    telemetry_enabled = False
    telemetry_level = "off"
  return {
    "version": __version__,
    "db_path": str(db_path),
    "db_exists": db_path.exists(),
    "telemetry_enabled": telemetry_enabled,
    "telemetry_level": telemetry_level,
  }


@mcp.tool()
def feature_implement_started(
  feature_size: str,
  acceptance_criteria_count: int,
  open_questions_count: int,
  spec_input_types: list[str],
  spec_word_count: int,
  rollout_needed: bool,
  feature_name: str = "",
  issue_key: str = "",
  issue_key_type: str = "none",
  spec_summary: str = "",
) -> dict:
  """Record the start of a feature-implement session.

  Call this after the Step 1 assessment is confirmed by the user.
  Returns a session_id to pass to feature_implement_finished later.
  """
  session_id = generate_feature_session_id()
  issue_key_provided = bool(issue_key.strip())

  validation_error = validate_started_params(
    feature_size=feature_size,
    issue_key_type=issue_key_type,
    spec_input_types=spec_input_types,
  )
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, db_path):
    save_started(
      connection,
      session_id=session_id,
      issue_key_provided=issue_key_provided,
      issue_key_type=issue_key_type,
      spec_input_types=spec_input_types,
      spec_word_count=spec_word_count,
      feature_size=feature_size,
      feature_name=feature_name,
      rollout_needed=rollout_needed,
      acceptance_criteria_count=acceptance_criteria_count,
      open_questions_count=open_questions_count,
      spec_summary=spec_summary,
    )
    settings = load_telemetry_settings()
    emit_started(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def feature_implement_finished(
  session_id: str,
  completion_status: str,
  plan_correction_count: int,
  plan_task_count: int,
  plan_phase_count: int,
  feature_flag_used: bool,
  files_created: int,
  files_modified: int,
  tasks_completed: int,
  review_iterations: int,
  audit_result: str,
  audit_iterations: int,
  validation_result: str,
  boundary_history_written: bool,
  pr_created: bool,
  feature_flag_pattern: str = "none",
  boundary_history_value: str = "none",
  plan_deviation_notes: str = "",
) -> dict:
  """Record the completion of a feature-implement session.

  Call this after Step 9 (PR created) or when the workflow ends early.
  The session_id must match the value returned by feature_implement_started.
  """
  validation_error = validate_finished_params(
    completion_status=completion_status,
    feature_flag_pattern=feature_flag_pattern,
    audit_result=audit_result,
    validation_result=validation_result,
    boundary_history_value=boundary_history_value,
  )
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, db_path):
    save_finished(
      connection,
      session_id=session_id,
      completion_status=completion_status,
      plan_correction_count=plan_correction_count,
      plan_task_count=plan_task_count,
      plan_phase_count=plan_phase_count,
      feature_flag_used=feature_flag_used,
      feature_flag_pattern=feature_flag_pattern,
      files_created=files_created,
      files_modified=files_modified,
      tasks_completed=tasks_completed,
      review_iterations=review_iterations,
      audit_result=audit_result,
      audit_iterations=audit_iterations,
      validation_result=validation_result,
      boundary_history_written=boundary_history_written,
      boundary_history_value=boundary_history_value,
      pr_created=pr_created,
      plan_deviation_notes=plan_deviation_notes,
    )
    settings = load_telemetry_settings()
    emit_finished(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


def main() -> None:
  mcp.run()


if __name__ == "__main__":
  main()
