from __future__ import annotations

import json

from mcp.server.fastmcp import FastMCP

from skill_bill import __version__
from skill_bill import feature_verify as _fv
from skill_bill import pr_description as _prd
from skill_bill import quality_check as _qc
from skill_bill import scaffold as _scaffold
from skill_bill import scaffold_domain as _scaffold_domain
from skill_bill.config import load_telemetry_settings, telemetry_is_enabled
from skill_bill.constants import LEARNING_SCOPE_PRECEDENCE
from skill_bill.db import open_db, resolve_db_path
from skill_bill.feature_implement import (
  continue_workflow,
  build_workflow_resume_payload,
  build_workflow_payload,
  build_workflow_summary_payload,
  emit_finished,
  emit_started,
  fetch_latest_workflow,
  list_workflows,
  generate_feature_session_id,
  generate_feature_workflow_id,
  save_workflow_open,
  save_workflow_state,
  save_finished,
  save_started,
  validate_finished_params,
  validate_started_params,
  validate_workflow_open_params,
  validate_workflow_state_params,
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
from skill_bill.stats import (
  feature_implement_stats_payload,
  feature_verify_stats_payload,
  stats_payload,
  update_review_finished_telemetry_state,
)
from skill_bill.sync import fetch_proxy_capabilities, fetch_remote_stats
from skill_bill.triage import (
  parse_triage_decisions,
  record_feedback,
)

mcp = FastMCP("skill-bill")


@mcp.tool()
def import_review(review_text: str, orchestrated: bool = False) -> dict:
  """Import code review output into the local telemetry store.

  Pass the complete review output text (Sections 1 through 4) as produced by
  a bill-code-review skill execution. The review must contain a Review run ID
  and Review session ID line. Findings are parsed from the risk register.

  In orchestrated mode: the review is still persisted locally (learnings and
  stats still work), but the review_runs row is marked so that
  skillbill_review_finished is never emitted on its own. If the review has no
  findings (auto-resolves immediately), the finished payload is returned as
  telemetry_payload for the orchestrator to embed in its own finished event.
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
    if orchestrated:
      with connection:
        connection.execute(
          "UPDATE review_runs SET orchestrated_run = 1 WHERE review_run_id = ?",
          (review.review_run_id,),
        )
    payload: dict[str, object] | None = None
    if len(review.findings) == 0:
      payload = update_review_finished_telemetry_state(
        connection,
        review_run_id=review.review_run_id,
      )
  result: dict[str, object] = {
    "db_path": str(db_path),
    "review_run_id": review.review_run_id,
    "review_session_id": review.review_session_id,
    "finding_count": len(review.findings),
    "routed_skill": review.routed_skill,
    "detected_scope": review.detected_scope,
    "detected_stack": review.detected_stack,
    "execution_mode": review.execution_mode,
  }
  if orchestrated:
    result["mode"] = "orchestrated"
    if payload is not None:
      enriched = dict(payload)
      enriched["skill"] = "bill-code-review"
      result["telemetry_payload"] = enriched
  return result


@mcp.tool()
def triage_findings(
  review_run_id: str,
  decisions: list[str],
  orchestrated: bool = False,
) -> dict:
  """Record triage decisions for findings in an imported review run.

  Each decision is a string like '1 fix', '2 skip - intentional',
  '3 accept', 'all fix', or a structured selection like
  'fix=[1,3] reject=[2]'. The numbers refer to the finding positions
  in the risk register.

  Valid actions: fix, accept, skip, edit, false_positive.
  Append a reason after ' - ' for skip and false_positive decisions.

  In orchestrated mode: feedback is still persisted locally, but no
  skillbill_review_finished event is emitted. When the final decision fully
  resolves the review, the finished payload is returned as
  telemetry_payload for the orchestrator to embed in its own finished event.
  """
  if not telemetry_is_enabled():
    return {
      "status": "skipped",
      "reason": "telemetry is disabled",
      "review_run_id": review_run_id,
    }
  with open_db() as (connection, db_path):
    if orchestrated:
      with connection:
        connection.execute(
          "UPDATE review_runs SET orchestrated_run = 1 WHERE review_run_id = ?",
          (review_run_id,),
        )
    numbered_findings = fetch_numbered_findings(connection, review_run_id)
    parsed_decisions = parse_triage_decisions(decisions, numbered_findings)
    last_payload: dict[str, object] | None = None
    for decision in parsed_decisions:
      returned_payload = record_feedback(
        connection,
        review_run_id=review_run_id,
        finding_ids=[decision.finding_id],
        event_type=decision.outcome_type,
        note=decision.note,
      )
      if returned_payload is not None:
        last_payload = returned_payload
  result: dict[str, object] = {
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
  if orchestrated:
    result["mode"] = "orchestrated"
    if last_payload is not None:
      enriched = dict(last_payload)
      enriched["skill"] = "bill-code-review"
      result["telemetry_payload"] = enriched
  return result


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
  with open_db(sync=False) as (connection, db_path):
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
def feature_implement_stats() -> dict:
  """Show aggregate bill-feature-implement metrics from the local SQLite store."""
  with open_db(sync=False) as (connection, db_path):
    payload = feature_implement_stats_payload(connection)
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_stats() -> dict:
  """Show aggregate bill-feature-verify metrics from the local SQLite store."""
  with open_db(sync=False) as (connection, db_path):
    payload = feature_verify_stats_payload(connection)
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def telemetry_remote_stats(
  workflow: str,
  since: str = "",
  date_from: str = "",
  date_to: str = "",
  group_by: str = "",
) -> dict:
  """Fetch aggregate org-wide workflow metrics from the configured telemetry proxy."""
  return fetch_remote_stats(
    workflow=workflow,
    since=since,
    date_from=date_from,
    date_to=date_to,
    group_by=group_by,
  )


@mcp.tool()
def telemetry_proxy_capabilities() -> dict:
  """Show which relay/proxy telemetry operations the configured endpoint supports."""
  return fetch_proxy_capabilities()


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
def feature_implement_workflow_open(
  session_id: str = "",
  current_step_id: str = "assess",
) -> dict:
  """Open durable workflow state for bill-feature-implement."""
  workflow_id = generate_feature_workflow_id()
  validation_error = validate_workflow_open_params(current_step_id=current_step_id)
  if validation_error:
    return {"status": "error", "workflow_id": workflow_id, "error": validation_error}

  with open_db(sync=False) as (connection, db_path):
    save_workflow_open(
      connection,
      workflow_id=workflow_id,
      session_id=session_id.strip(),
      current_step_id=current_step_id,
    )
    payload = build_workflow_payload(connection, workflow_id)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_implement_workflow_update(
  workflow_id: str,
  workflow_status: str,
  current_step_id: str = "",
  step_updates: list[dict] | None = None,
  artifacts_patch: dict | None = None,
  session_id: str = "",
) -> dict:
  """Update durable workflow state for bill-feature-implement."""
  validation_error = validate_workflow_state_params(
    workflow_status=workflow_status,
    current_step_id=current_step_id,
    step_updates=step_updates,
    artifacts_patch=artifacts_patch,
  )
  if validation_error:
    return {"status": "error", "workflow_id": workflow_id, "error": validation_error}

  with open_db(sync=False) as (connection, db_path):
    updated = save_workflow_state(
      connection,
      workflow_id=workflow_id,
      workflow_status=workflow_status,
      current_step_id=current_step_id.strip(),
      step_updates=step_updates,
      artifacts_patch=artifacts_patch,
      session_id=session_id.strip(),
    )
    if not updated:
      return {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
      }
    payload = build_workflow_payload(connection, workflow_id)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_implement_workflow_get(workflow_id: str) -> dict:
  """Fetch durable workflow state for bill-feature-implement."""
  with open_db(sync=False) as (connection, db_path):
    payload = build_workflow_payload(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_implement_workflow_list(limit: int = 20) -> dict:
  """List recent persisted bill-feature-implement workflow runs."""
  with open_db(sync=False) as (connection, db_path):
    rows = list_workflows(connection, limit=limit)
  return {
    "status": "ok",
    "db_path": str(db_path),
    "workflow_count": len(rows),
    "workflows": [build_workflow_summary_payload(row) for row in rows],
  }


@mcp.tool()
def feature_implement_workflow_latest() -> dict:
  """Fetch the most recently updated bill-feature-implement workflow run."""
  with open_db(sync=False) as (connection, db_path):
    row = fetch_latest_workflow(connection)
  if row is None:
    return {
      "status": "error",
      "error": "No feature-implement workflows found.",
      "db_path": str(db_path),
    }
  payload = build_workflow_summary_payload(row)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_implement_workflow_resume(workflow_id: str) -> dict:
  """Summarize how to resume or recover a bill-feature-implement workflow."""
  with open_db(sync=False) as (connection, db_path):
    payload = build_workflow_resume_payload(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_implement_workflow_continue(workflow_id: str) -> dict:
  """Reopen a resumable workflow and return a recovered continuation brief."""
  with open_db(sync=False) as (connection, db_path):
    payload = continue_workflow(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["db_path"] = str(db_path)
  if payload["continue_status"] == "blocked":
    payload["status"] = "error"
    missing_artifacts = payload.get("missing_artifacts", [])
    assert isinstance(missing_artifacts, list)
    payload["error"] = (
      "Cannot continue workflow until the missing artifacts are restored: "
      + ", ".join(str(value) for value in missing_artifacts)
    )
    return payload
  payload["status"] = "ok"
  return payload


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
  child_steps: list[dict] | None = None,
) -> dict:
  """Record the completion of a feature-implement session.

  Call this after Step 9 (PR created) or when the workflow ends early.
  The session_id must match the value returned by feature_implement_started.

  child_steps: optional list of telemetry_payload dicts returned by child
  tools invoked with orchestrated=True during the session (e.g. orchestrated
  bill-code-review, bill-quality-check, bill-pr-description). These are
  embedded in the emitted skillbill_feature_implement_finished event so the
  full workflow produces exactly one telemetry event.
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
      child_steps=child_steps,
    )
    settings = load_telemetry_settings()
    emit_finished(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def quality_check_started(
  routed_skill: str,
  detected_stack: str,
  scope_type: str,
  initial_failure_count: int,
  orchestrated: bool = False,
) -> dict:
  """Record the start of a quality-check session.

  In standalone mode (orchestrated=False): persist a session row and emit
  skillbill_quality_check_started. Returns a session_id to pass to
  quality_check_finished later.

  In orchestrated mode (orchestrated=True): this call is a no-op. The
  orchestrator should call quality_check_finished with all started+finished
  fields instead so one consolidated payload can be embedded in the parent
  event.
  """
  if orchestrated:
    return {"mode": "orchestrated", "status": "skipped_in_orchestrated_mode"}

  validation_error = _qc.validate_started_params(scope_type=scope_type)
  session_id = _qc.generate_quality_check_session_id()
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, _db_path):
    _qc.save_started(
      connection,
      session_id=session_id,
      routed_skill=routed_skill,
      detected_stack=detected_stack,
      scope_type=scope_type,
      initial_failure_count=initial_failure_count,
    )
    settings = load_telemetry_settings()
    _qc.emit_started(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def quality_check_finished(
  final_failure_count: int,
  iterations: int,
  result: str,
  session_id: str = "",
  failing_check_names: list[str] | None = None,
  unsupported_reason: str = "",
  orchestrated: bool = False,
  routed_skill: str = "",
  detected_stack: str = "",
  scope_type: str = "",
  initial_failure_count: int = 0,
  duration_seconds: int = 0,
) -> dict:
  """Record the completion of a quality-check session.

  Standalone mode (orchestrated=False): session_id must match the value
  returned by quality_check_started. Persists and emits
  skillbill_quality_check_finished.

  Orchestrated mode (orchestrated=True): session_id is ignored. All started
  fields (routed_skill, detected_stack, scope_type, initial_failure_count)
  must be passed here too. Returns a telemetry_payload that the orchestrator
  should embed in its own finished event's child_steps array. No local event
  is emitted.
  """
  failing_check_names_list = list(failing_check_names or [])
  validation_error = _qc.validate_finished_params(result=result)
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  if orchestrated:
    level = "anonymous"
    try:
      settings = load_telemetry_settings()
      level = settings.level
    except ValueError:
      level = "anonymous"
    payload = _qc.build_finished_payload_from_fields(
      session_id="",
      routed_skill=routed_skill,
      detected_stack=detected_stack,
      scope_type=scope_type,
      initial_failure_count=initial_failure_count,
      final_failure_count=final_failure_count,
      iterations=iterations,
      result=result,
      failing_check_names=failing_check_names_list,
      unsupported_reason=unsupported_reason,
      duration_seconds=duration_seconds,
      level=level,
    )
    payload.pop("session_id", None)
    payload["skill"] = "bill-quality-check"
    return {"mode": "orchestrated", "telemetry_payload": payload}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, _db_path):
    _qc.save_finished(
      connection,
      session_id=session_id,
      final_failure_count=final_failure_count,
      iterations=iterations,
      result=result,
      failing_check_names=failing_check_names_list,
      unsupported_reason=unsupported_reason,
    )
    settings = load_telemetry_settings()
    _qc.emit_finished(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def feature_verify_started(
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str = "",
  orchestrated: bool = False,
) -> dict:
  """Record the start of a feature-verify session.

  Standalone mode: persist a session row and emit
  skillbill_feature_verify_started. Returns a session_id.

  Orchestrated mode: no-op. The orchestrator should call
  feature_verify_finished with all started+finished fields.
  """
  if orchestrated:
    return {"mode": "orchestrated", "status": "skipped_in_orchestrated_mode"}

  session_id = _fv.generate_feature_verify_session_id()

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, _db_path):
    _fv.save_started(
      connection,
      session_id=session_id,
      acceptance_criteria_count=acceptance_criteria_count,
      rollout_relevant=rollout_relevant,
      spec_summary=spec_summary,
    )
    settings = load_telemetry_settings()
    _fv.emit_started(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def feature_verify_finished(
  feature_flag_audit_performed: bool,
  review_iterations: int,
  audit_result: str,
  completion_status: str,
  history_relevance: str = "none",
  history_helpfulness: str = "none",
  session_id: str = "",
  gaps_found: list[str] | None = None,
  orchestrated: bool = False,
  acceptance_criteria_count: int = 0,
  rollout_relevant: bool = False,
  spec_summary: str = "",
  duration_seconds: int = 0,
) -> dict:
  """Record the completion of a feature-verify session.

  Standalone: session_id required, persists and emits
  skillbill_feature_verify_finished.

  Orchestrated: session_id ignored, all started fields must be supplied.
  Returns telemetry_payload for the orchestrator to embed in its own event.
  """
  gaps_found_list = list(gaps_found or [])
  validation_error = _fv.validate_finished_params(
    audit_result=audit_result,
    completion_status=completion_status,
    history_relevance=history_relevance,
    history_helpfulness=history_helpfulness,
  )
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  if orchestrated:
    level = "anonymous"
    try:
      settings = load_telemetry_settings()
      level = settings.level
    except ValueError:
      level = "anonymous"
    payload = _fv.build_finished_payload_from_fields(
      session_id="",
      acceptance_criteria_count=acceptance_criteria_count,
      rollout_relevant=rollout_relevant,
      spec_summary=spec_summary,
      feature_flag_audit_performed=feature_flag_audit_performed,
      review_iterations=review_iterations,
      audit_result=audit_result,
      completion_status=completion_status,
      history_relevance=history_relevance,
      history_helpfulness=history_helpfulness,
      gaps_found=gaps_found_list,
      duration_seconds=duration_seconds,
      level=level,
    )
    payload.pop("session_id", None)
    payload["skill"] = "bill-feature-verify"
    return {"mode": "orchestrated", "telemetry_payload": payload}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, _db_path):
    _fv.save_finished(
      connection,
      session_id=session_id,
      feature_flag_audit_performed=feature_flag_audit_performed,
      review_iterations=review_iterations,
      audit_result=audit_result,
      completion_status=completion_status,
      history_relevance=history_relevance,
      history_helpfulness=history_helpfulness,
      gaps_found=gaps_found_list,
    )
    settings = load_telemetry_settings()
    _fv.emit_finished(
      connection,
      session_id=session_id,
      enabled=settings.enabled,
      level=settings.level,
    )
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def feature_verify_workflow_open(
  session_id: str = "",
  current_step_id: str = "gather_diff",
) -> dict:
  """Open durable workflow state for bill-feature-verify."""
  workflow_id = _fv.generate_feature_verify_workflow_id()
  validation_error = _fv.validate_workflow_open_params(current_step_id=current_step_id)
  if validation_error:
    return {"status": "error", "workflow_id": workflow_id, "error": validation_error}

  with open_db(sync=False) as (connection, db_path):
    _fv.save_workflow_open(
      connection,
      workflow_id=workflow_id,
      session_id=session_id.strip(),
      current_step_id=current_step_id,
    )
    payload = _fv.build_workflow_payload(connection, workflow_id)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_workflow_update(
  workflow_id: str,
  workflow_status: str,
  current_step_id: str = "",
  step_updates: list[dict] | None = None,
  artifacts_patch: dict | None = None,
  session_id: str = "",
) -> dict:
  """Update durable workflow state for bill-feature-verify."""
  validation_error = _fv.validate_workflow_state_params(
    workflow_status=workflow_status,
    current_step_id=current_step_id,
    step_updates=step_updates,
    artifacts_patch=artifacts_patch,
  )
  if validation_error:
    return {"status": "error", "workflow_id": workflow_id, "error": validation_error}

  with open_db(sync=False) as (connection, db_path):
    updated = _fv.save_workflow_state(
      connection,
      workflow_id=workflow_id,
      workflow_status=workflow_status,
      current_step_id=current_step_id.strip(),
      step_updates=step_updates,
      artifacts_patch=artifacts_patch,
      session_id=session_id.strip(),
    )
    if not updated:
      return {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
      }
    payload = _fv.build_workflow_payload(connection, workflow_id)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_workflow_get(workflow_id: str) -> dict:
  """Fetch durable workflow state for bill-feature-verify."""
  with open_db(sync=False) as (connection, db_path):
    payload = _fv.build_workflow_payload(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_workflow_list(limit: int = 20) -> dict:
  """List recent persisted bill-feature-verify workflow runs."""
  with open_db(sync=False) as (connection, db_path):
    rows = _fv.list_workflows(connection, limit=limit)
  return {
    "status": "ok",
    "db_path": str(db_path),
    "workflow_count": len(rows),
    "workflows": [_fv.build_workflow_summary_payload(row) for row in rows],
  }


@mcp.tool()
def feature_verify_workflow_latest() -> dict:
  """Fetch the most recently updated bill-feature-verify workflow run."""
  with open_db(sync=False) as (connection, db_path):
    row = _fv.fetch_latest_workflow(connection)
  if row is None:
    return {
      "status": "error",
      "error": "No feature-verify workflows found.",
      "db_path": str(db_path),
    }
  payload = _fv.build_workflow_summary_payload(row)
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_workflow_resume(workflow_id: str) -> dict:
  """Summarize how to resume or recover a bill-feature-verify workflow."""
  with open_db(sync=False) as (connection, db_path):
    payload = _fv.build_workflow_resume_payload(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  return payload


@mcp.tool()
def feature_verify_workflow_continue(workflow_id: str) -> dict:
  """Reopen a resumable workflow and return a recovered continuation brief."""
  with open_db(sync=False) as (connection, db_path):
    payload = _fv.continue_workflow(connection, workflow_id)
  if not payload:
    return {
      "status": "error",
      "workflow_id": workflow_id,
      "error": f"Unknown workflow_id '{workflow_id}'.",
    }
  payload["db_path"] = str(db_path)
  if payload["continue_status"] == "blocked":
    payload["status"] = "error"
    missing_artifacts = payload.get("missing_artifacts", [])
    assert isinstance(missing_artifacts, list)
    payload["error"] = (
      "Cannot continue workflow until the missing artifacts are restored: "
      + ", ".join(str(value) for value in missing_artifacts)
    )
    return payload
  payload["status"] = "ok"
  return payload


@mcp.tool()
def pr_description_generated(
  commit_count: int,
  files_changed_count: int,
  was_edited_by_user: bool,
  pr_created: bool,
  pr_title: str = "",
  orchestrated: bool = False,
) -> dict:
  """Record a PR description generation.

  One-shot event. Standalone: emit skillbill_pr_description_generated.
  Orchestrated: return telemetry_payload for the orchestrator to embed in
  its own finished event.
  """
  level = "anonymous"
  try:
    settings = load_telemetry_settings()
    level = settings.level
  except ValueError:
    level = "anonymous"

  session_id = "" if orchestrated else _prd.generate_pr_description_session_id()
  payload = _prd.build_payload(
    session_id=session_id,
    commit_count=commit_count,
    files_changed_count=files_changed_count,
    was_edited_by_user=was_edited_by_user,
    pr_created=pr_created,
    pr_title=pr_title,
    level=level,
  )

  if orchestrated:
    payload.pop("session_id", None)
    payload["skill"] = "bill-pr-description"
    return {"mode": "orchestrated", "telemetry_payload": payload}

  if not telemetry_is_enabled():
    return {"status": "skipped", "session_id": session_id}

  with open_db() as (connection, _db_path):
    _prd.emit_event(connection, payload=payload, enabled=True)
  return {"status": "ok", "session_id": session_id}


@mcp.tool()
def new_skill_scaffold(
  payload: dict,
  dry_run: bool = False,
  orchestrated: bool = False,
) -> dict:
  """Scaffold a new skill from a validated payload.

  Standalone (orchestrated=False): invoke the scaffolder, run the validator,
  install into detected agents, and emit skillbill_new_skill_scaffold_started
  and skillbill_new_skill_scaffold_finished events.

  Orchestrated (orchestrated=True): the scaffold still runs, but the finished
  event is returned as ``telemetry_payload`` so the parent ceremony can embed
  it in its own ``child_steps`` array. Started events are skipped in this
  mode (parents own the lifecycle).
  """
  validation_error = _scaffold_domain.validate_scaffold_params(
    kind=str(payload.get("kind", ""))
  )
  session_id = _scaffold_domain.generate_new_skill_session_id()
  if validation_error:
    return {"status": "error", "session_id": session_id, "error": validation_error}

  level = "anonymous"
  try:
    settings = load_telemetry_settings()
    level = settings.level
  except ValueError:
    level = "anonymous"

  started_payload = _scaffold_domain.build_started_payload_from_fields(
    session_id=session_id,
    kind=str(payload.get("kind", "")),
    skill_name=str(payload.get("name", "")),
    platform=str(payload.get("platform", "")),
    family=str(payload.get("family", "")),
    area=str(payload.get("area", "")),
    level=level,
  )

  try:
    result = _scaffold.scaffold(payload, dry_run=dry_run)
    outcome = "dry-run" if dry_run else "success"
  except Exception as error:  # noqa: BLE001 — surface to caller
    outcome = "failed"
    if orchestrated:
      payload_out = dict(started_payload)
      payload_out.update({"result": outcome, "error": str(error), "skill": "bill-create-skill"})
      payload_out.pop("session_id", None)
      return {"mode": "orchestrated", "telemetry_payload": payload_out, "error": str(error)}
    return {"status": "error", "session_id": session_id, "error": str(error)}

  finished_payload = _scaffold_domain.build_finished_payload_from_fields(
    session_id=session_id,
    kind=result.kind,
    skill_name=result.skill_name,
    platform=str(payload.get("platform", "")),
    family=str(payload.get("family", "")),
    area=str(payload.get("area", "")),
    result=outcome,
    duration_seconds=0,
    level=level,
  )

  if orchestrated:
    finished_payload.pop("session_id", None)
    finished_payload["skill"] = "bill-create-skill"
    return {
      "mode": "orchestrated",
      "telemetry_payload": finished_payload,
      "skill_path": str(result.skill_path),
      "notes": result.notes,
    }

  if not telemetry_is_enabled():
    return {
      "status": "ok",
      "session_id": session_id,
      "skill_path": str(result.skill_path),
      "notes": result.notes,
    }

  with open_db() as (connection, _db_path):
    _scaffold_domain.save_scaffold_record(
      connection,
      session_id=session_id,
      kind=result.kind,
      skill_name=result.skill_name,
      platform=str(payload.get("platform", "")),
      family=str(payload.get("family", "")),
      area=str(payload.get("area", "")),
      result=outcome,
    )
    _scaffold_domain.emit_new_skill_scaffold_started(
      connection, payload=started_payload, enabled=True
    )
    _scaffold_domain.emit_new_skill_scaffold_finished(
      connection, payload=finished_payload, enabled=True
    )
  return {
    "status": "ok",
    "session_id": session_id,
    "skill_path": str(result.skill_path),
    "notes": result.notes,
  }


def main() -> None:
  mcp.run()


if __name__ == "__main__":
  main()
