from __future__ import annotations

import json
import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  AUDIT_RESULTS,
  EVENT_FEATURE_VERIFY_FINISHED,
  EVENT_FEATURE_VERIFY_STARTED,
  FEATURE_VERIFY_COMPLETION_STATUSES,
  FEATURE_VERIFY_SESSION_PREFIX,
  FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
  FEATURE_VERIFY_WORKFLOW_PREFIX,
  FEATURE_VERIFY_WORKFLOW_STATUSES,
  FEATURE_VERIFY_WORKFLOW_STEP_IDS,
  FEATURE_VERIFY_WORKFLOW_STEP_STATUSES,
  FEATURE_VERIFY_WORKFLOW_TERMINAL_STATUSES,
)
from skill_bill.feature_implement import validate_enum
from skill_bill.stats import enqueue_telemetry_event


def generate_feature_verify_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{FEATURE_VERIFY_SESSION_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_finished_params(
  *,
  audit_result: str,
  completion_status: str,
) -> str | None:
  error = validate_enum(audit_result, AUDIT_RESULTS, "audit_result")
  if error:
    return error
  return validate_enum(
    completion_status,
    FEATURE_VERIFY_COMPLETION_STATUSES,
    "completion_status",
  )


def save_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO feature_verify_sessions (
        session_id, acceptance_criteria_count, rollout_relevant, spec_summary
      ) VALUES (?, ?, ?, ?)
      """,
      (
        session_id,
        acceptance_criteria_count,
        1 if rollout_relevant else 0,
        spec_summary,
      ),
    )


def save_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  feature_flag_audit_performed: bool,
  review_iterations: int,
  audit_result: str,
  completion_status: str,
  gaps_found: list[str],
) -> None:
  exists = connection.execute(
    "SELECT 1 FROM feature_verify_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()

  if exists:
    with connection:
      connection.execute(
        """
        UPDATE feature_verify_sessions SET
          feature_flag_audit_performed = ?,
          review_iterations = ?,
          audit_result = ?,
          completion_status = ?,
          gaps_found = ?,
          finished_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (
          1 if feature_flag_audit_performed else 0,
          review_iterations,
          audit_result,
          completion_status,
          json.dumps(gaps_found),
          session_id,
        ),
      )
  else:
    with connection:
      connection.execute(
        """
        INSERT INTO feature_verify_sessions (
          session_id, feature_flag_audit_performed, review_iterations,
          audit_result, completion_status, gaps_found, finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        (
          session_id,
          1 if feature_flag_audit_performed else 0,
          review_iterations,
          audit_result,
          completion_status,
          json.dumps(gaps_found),
        ),
      )


def fetch_session(connection: sqlite3.Connection, session_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_verify_sessions WHERE session_id = ?",
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
    acceptance_criteria_count=row["acceptance_criteria_count"],
    rollout_relevant=bool(row["rollout_relevant"]),
    spec_summary=row["spec_summary"],
    level=level,
  )


def build_started_payload_from_fields(
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
  level: str,
) -> dict[str, object]:
  payload: dict[str, object] = {
    "session_id": session_id,
    "acceptance_criteria_count": acceptance_criteria_count,
    "rollout_relevant": rollout_relevant,
  }
  if level == "full":
    payload["spec_summary"] = spec_summary
  return payload


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

  gaps_found = json.loads(row["gaps_found"] or "[]")

  payload.update({
    "feature_flag_audit_performed": bool(row["feature_flag_audit_performed"]),
    "review_iterations": row["review_iterations"] or 0,
    "audit_result": row["audit_result"] or "skipped",
    "completion_status": row["completion_status"] or "",
    "duration_seconds": duration_seconds,
  })

  if level == "full":
    payload["gaps_found"] = gaps_found

  return payload


def build_finished_payload_from_fields(
  *,
  session_id: str,
  acceptance_criteria_count: int,
  rollout_relevant: bool,
  spec_summary: str,
  feature_flag_audit_performed: bool,
  review_iterations: int,
  audit_result: str,
  completion_status: str,
  gaps_found: list[str],
  duration_seconds: int,
  level: str,
) -> dict[str, object]:
  payload = build_started_payload_from_fields(
    session_id=session_id,
    acceptance_criteria_count=acceptance_criteria_count,
    rollout_relevant=rollout_relevant,
    spec_summary=spec_summary,
    level=level,
  )
  payload.update({
    "feature_flag_audit_performed": feature_flag_audit_performed,
    "review_iterations": review_iterations,
    "audit_result": audit_result,
    "completion_status": completion_status,
    "duration_seconds": duration_seconds,
  })
  if level == "full":
    payload["gaps_found"] = list(gaps_found)
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
      event_name=EVENT_FEATURE_VERIFY_STARTED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_verify_sessions
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
      event_name=EVENT_FEATURE_VERIFY_FINISHED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_verify_sessions
        SET finished_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )


def generate_feature_verify_workflow_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{FEATURE_VERIFY_WORKFLOW_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def _default_workflow_steps(initial_step_id: str) -> list[dict[str, object]]:
  steps: list[dict[str, object]] = []
  seen_initial = False
  for step_id in FEATURE_VERIFY_WORKFLOW_STEP_IDS:
    if step_id == initial_step_id:
      seen_initial = True
      steps.append({
        "step_id": step_id,
        "status": "running",
        "attempt_count": 1,
      })
      continue
    if not seen_initial:
      steps.append({
        "step_id": step_id,
        "status": "completed",
        "attempt_count": 1,
      })
      continue
    steps.append({
      "step_id": step_id,
      "status": "pending",
      "attempt_count": 0,
    })
  return steps


def validate_workflow_open_params(*, current_step_id: str) -> str | None:
  return validate_enum(current_step_id, FEATURE_VERIFY_WORKFLOW_STEP_IDS, "current_step_id")


def validate_workflow_state_params(
  *,
  workflow_status: str,
  current_step_id: str,
  step_updates: list[dict] | None,
  artifacts_patch: dict | None,
) -> str | None:
  error = validate_enum(workflow_status, FEATURE_VERIFY_WORKFLOW_STATUSES, "workflow_status")
  if error:
    return error
  if current_step_id:
    error = validate_enum(current_step_id, FEATURE_VERIFY_WORKFLOW_STEP_IDS, "current_step_id")
    if error:
      return error
  if step_updates is not None:
    if not isinstance(step_updates, list):
      return "step_updates must be a list of step objects."
    seen_step_ids: set[str] = set()
    for index, step in enumerate(step_updates):
      if not isinstance(step, dict):
        return f"step_updates[{index}] must be an object."
      step_id = step.get("step_id")
      status = step.get("status")
      attempt_count = step.get("attempt_count")
      if not isinstance(step_id, str) or not step_id:
        return f"step_updates[{index}].step_id must be a non-empty string."
      error = validate_enum(step_id, FEATURE_VERIFY_WORKFLOW_STEP_IDS, "step_updates.step_id")
      if error:
        return error
      if step_id in seen_step_ids:
        return f"Duplicate step_id '{step_id}' in step_updates."
      seen_step_ids.add(step_id)
      if not isinstance(status, str) or not status:
        return f"step_updates[{index}].status must be a non-empty string."
      error = validate_enum(status, FEATURE_VERIFY_WORKFLOW_STEP_STATUSES, "step_updates.status")
      if error:
        return error
      if not isinstance(attempt_count, int) or attempt_count < 0:
        return f"step_updates[{index}].attempt_count must be an integer >= 0."
  if artifacts_patch is not None and not isinstance(artifacts_patch, dict):
    return "artifacts_patch must be an object."
  return None


def _decode_json_object(raw: str, *, default: dict | list) -> dict | list:
  if not raw:
    return default
  try:
    decoded = json.loads(raw)
  except json.JSONDecodeError:
    return default
  if isinstance(default, dict) and isinstance(decoded, dict):
    return decoded
  if isinstance(default, list) and isinstance(decoded, list):
    return decoded
  return default


def _merge_step_updates(
  existing_steps: list[dict[str, object]],
  step_updates: list[dict] | None,
) -> list[dict[str, object]]:
  if step_updates is None:
    return existing_steps

  by_step_id = {str(step["step_id"]): dict(step) for step in existing_steps}
  for step_update in step_updates:
    step_id = str(step_update["step_id"])
    by_step_id[step_id] = {
      "step_id": step_id,
      "status": str(step_update["status"]),
      "attempt_count": int(step_update["attempt_count"]),
    }

  return [
    by_step_id[step_id]
    for step_id in FEATURE_VERIFY_WORKFLOW_STEP_IDS
    if step_id in by_step_id
  ]


def save_workflow_open(
  connection: sqlite3.Connection,
  *,
  workflow_id: str,
  session_id: str,
  current_step_id: str,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO feature_verify_workflows (
        workflow_id, session_id, workflow_name, contract_version,
        workflow_status, current_step_id, steps_json, artifacts_json
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """,
      (
        workflow_id,
        session_id,
        "bill-feature-verify",
        FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
        "running",
        current_step_id,
        json.dumps(_default_workflow_steps(current_step_id), sort_keys=True),
        json.dumps({}, sort_keys=True),
      ),
    )


def fetch_workflow(connection: sqlite3.Connection, workflow_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_verify_workflows WHERE workflow_id = ?",
    (workflow_id,),
  ).fetchone()


def fetch_latest_workflow(connection: sqlite3.Connection) -> sqlite3.Row | None:
  return connection.execute(
    """
    SELECT *
    FROM feature_verify_workflows
    ORDER BY updated_at DESC, rowid DESC
    LIMIT 1
    """
  ).fetchone()


def list_workflows(connection: sqlite3.Connection, limit: int = 20) -> list[sqlite3.Row]:
  return list(connection.execute(
    """
    SELECT *
    FROM feature_verify_workflows
    ORDER BY updated_at DESC, rowid DESC
    LIMIT ?
    """,
    (limit,),
  ).fetchall())


def save_workflow_state(
  connection: sqlite3.Connection,
  *,
  workflow_id: str,
  workflow_status: str,
  current_step_id: str,
  step_updates: list[dict] | None,
  artifacts_patch: dict | None,
  session_id: str,
) -> bool:
  row = fetch_workflow(connection, workflow_id)
  if row is None:
    return False

  existing_steps = _decode_json_object(row["steps_json"] or "", default=[])
  assert isinstance(existing_steps, list)
  merged_steps = _merge_step_updates(existing_steps, step_updates)

  existing_artifacts = _decode_json_object(row["artifacts_json"] or "", default={})
  assert isinstance(existing_artifacts, dict)
  merged_artifacts = dict(existing_artifacts)
  if artifacts_patch:
    merged_artifacts.update(artifacts_patch)

  next_current_step_id = current_step_id or (row["current_step_id"] or "")
  next_session_id = session_id or (row["session_id"] or "")
  terminal = workflow_status in FEATURE_VERIFY_WORKFLOW_TERMINAL_STATUSES

  with connection:
    connection.execute(
      """
      UPDATE feature_verify_workflows
      SET session_id = ?,
          workflow_status = ?,
          current_step_id = ?,
          steps_json = ?,
          artifacts_json = ?,
          updated_at = CURRENT_TIMESTAMP,
          finished_at = CASE
            WHEN ? THEN CURRENT_TIMESTAMP
            ELSE NULL
          END
      WHERE workflow_id = ?
      """,
      (
        next_session_id,
        workflow_status,
        next_current_step_id,
        json.dumps(merged_steps, sort_keys=True),
        json.dumps(merged_artifacts, sort_keys=True),
        1 if terminal else 0,
        workflow_id,
      ),
    )
  return True


def build_workflow_payload(
  connection: sqlite3.Connection,
  workflow_id: str,
) -> dict[str, object]:
  row = fetch_workflow(connection, workflow_id)
  if row is None:
    return {}

  steps = _decode_json_object(row["steps_json"] or "", default=[])
  artifacts = _decode_json_object(row["artifacts_json"] or "", default={})
  assert isinstance(steps, list)
  assert isinstance(artifacts, dict)

  return {
    "workflow_id": row["workflow_id"],
    "session_id": row["session_id"] or "",
    "workflow_name": row["workflow_name"] or "bill-feature-verify",
    "contract_version": row["contract_version"] or FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
    "workflow_status": row["workflow_status"] or "pending",
    "current_step_id": row["current_step_id"] or "",
    "steps": steps,
    "artifacts": artifacts,
    "started_at": row["started_at"] or "",
    "updated_at": row["updated_at"] or "",
    "finished_at": row["finished_at"] or "",
  }


def build_workflow_summary_payload(row: sqlite3.Row) -> dict[str, object]:
  return {
    "workflow_id": row["workflow_id"],
    "session_id": row["session_id"] or "",
    "workflow_name": row["workflow_name"] or "bill-feature-verify",
    "contract_version": row["contract_version"] or FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
    "workflow_status": row["workflow_status"] or "pending",
    "current_step_id": row["current_step_id"] or "",
    "started_at": row["started_at"] or "",
    "updated_at": row["updated_at"] or "",
    "finished_at": row["finished_at"] or "",
  }


_WORKFLOW_REQUIRED_ARTIFACTS_BY_STEP: dict[str, list[str]] = {
  "collect_inputs": [],
  "extract_criteria": ["input_context"],
  "gather_diff": ["input_context", "criteria_summary"],
  "feature_flag_audit": ["criteria_summary", "diff_summary"],
  "code_review": ["criteria_summary", "diff_summary"],
  "completeness_audit": ["criteria_summary", "diff_summary", "review_result"],
  "verdict": ["criteria_summary", "review_result", "completeness_audit_result"],
  "finish": ["verdict_result"],
}


_WORKFLOW_RESUME_ACTIONS: dict[str, str] = {
  "collect_inputs": "Reconfirm the task spec and PR inputs, then reopen the workflow from extract_criteria.",
  "extract_criteria": "Re-extract and confirm the criteria, then persist criteria_summary before moving to gather_diff.",
  "gather_diff": "Reuse the saved input_context and criteria_summary artifacts, then gather the diff target and persist diff_summary.",
  "feature_flag_audit": "Reuse criteria_summary and diff_summary, run the feature-flag audit if it is still required, and persist feature_flag_audit_result.",
  "code_review": "Reuse criteria_summary and diff_summary, then invoke bill-code-review with orchestrated=true and persist review_result.",
  "completeness_audit": "Reuse criteria_summary, diff_summary, and review_result, then run the completeness audit and persist completeness_audit_result.",
  "verdict": "Reuse the saved review and audit artifacts, then write the consolidated verdict without rerunning earlier phases.",
  "finish": "Close the workflow by marking the verdict complete and emitting the terminal summary.",
}


_WORKFLOW_STEP_LABELS: dict[str, str] = {
  "collect_inputs": "Step 1: Collect Inputs",
  "extract_criteria": "Step 2: Extract Acceptance Criteria",
  "gather_diff": "Step 3: Gather PR Diff",
  "feature_flag_audit": "Step 4: Feature Flag Audit",
  "code_review": "Step 5: Code Review",
  "completeness_audit": "Step 6: Completeness Audit",
  "verdict": "Step 7: Consolidated Verdict",
  "finish": "Finish",
}


_WORKFLOW_CONTINUATION_REFERENCE_SECTIONS: dict[str, list[str]] = {
  "collect_inputs": [
    "SKILL.md :: Workflow State",
    "SKILL.md :: Step 1: Collect Inputs",
  ],
  "extract_criteria": [
    "SKILL.md :: Workflow State",
    "SKILL.md :: Step 2: Extract Acceptance Criteria",
  ],
  "gather_diff": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 3: Gather PR Diff",
  ],
  "feature_flag_audit": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 4: Feature Flag Audit (conditional)",
    "audit-rubrics.md :: Feature Flag Audit",
  ],
  "code_review": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 5: Code Review",
    "SKILL.md :: Nested child tools",
  ],
  "completeness_audit": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 6: Completeness Audit",
    "audit-rubrics.md :: Completeness Audit",
  ],
  "verdict": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 7: Consolidated Verdict",
    "audit-rubrics.md :: Consolidated Verdict",
  ],
  "finish": [
    "SKILL.md :: Telemetry",
    "SKILL.md :: Workflow State",
  ],
}


_WORKFLOW_CONTINUATION_DIRECTIVES: dict[str, str] = {
  "collect_inputs": "Reconfirm the spec and PR inputs before continuing, then reopen the workflow from Step 2 with the recovered context.",
  "extract_criteria": "Re-extract the criteria from the saved spec context, confirm them with the user, and persist criteria_summary before advancing.",
  "gather_diff": "Skip Steps 1 and 2. Reuse the saved input_context and criteria_summary artifacts, then gather the diff target and persist diff_summary.",
  "feature_flag_audit": "Reuse the saved criteria_summary and diff_summary artifacts. Run the audit only when the spec or diff still requires it, then persist feature_flag_audit_result.",
  "code_review": "Reuse the saved criteria_summary and diff_summary artifacts, pass orchestrated=true to bill-code-review, and store the returned telemetry payload with the review result.",
  "completeness_audit": "Reuse criteria_summary, diff_summary, and review_result. If the verify target changed materially, refresh the diff before re-running the audit.",
  "verdict": "Reuse the saved review and audit artifacts to produce the final verdict without rerunning earlier steps unless recovery made them stale.",
  "finish": "Do not re-run analysis. Close the workflow using the saved verdict_result and return the terminal summary only.",
}


def _workflow_continue_artifact_keys(
  *,
  resume_step_id: str,
  artifacts: dict[str, object],
) -> list[str]:
  ordered_keys: list[str] = []
  for key in (
    "input_context",
    "criteria_summary",
    "diff_summary",
    "feature_flag_audit_result",
    "review_result",
    "completeness_audit_result",
    "verdict_result",
    "session_notes",
    "review_diff_pointer",
  ):
    if key in artifacts:
      ordered_keys.append(key)
  for key in _WORKFLOW_REQUIRED_ARTIFACTS_BY_STEP.get(resume_step_id, []):
    if key in artifacts and key not in ordered_keys:
      ordered_keys.append(key)
  return ordered_keys


def _build_workflow_continuation_brief(
  *,
  workflow_id: str,
  resume_step_id: str,
  continue_status: str,
  next_action: str,
  artifact_keys: list[str],
) -> str:
  step_label = _WORKFLOW_STEP_LABELS.get(resume_step_id, resume_step_id)
  artifact_list = ", ".join(artifact_keys) if artifact_keys else "none"
  return (
    f"Resume `bill-feature-verify` workflow `{workflow_id}` from "
    f"`{step_label}` (`{resume_step_id}`). "
    "Follow the workflow instructions in `skills/bill-feature-verify/SKILL.md`. "
    f"Use the recovered `step_artifacts` in this payload ({artifact_list}) instead of reconstructing prior context from chat history. "
    f"Workflow activation status: `{continue_status}`. "
    f"Next action: {next_action}"
  )


def _build_workflow_continuation_entry_prompt(
  *,
  workflow_id: str,
  session_id: str,
  resume_step_id: str,
  continue_status: str,
  artifact_keys: list[str],
  next_action: str,
  session_summary: dict[str, object],
) -> str:
  references = "; ".join(_WORKFLOW_CONTINUATION_REFERENCE_SECTIONS.get(resume_step_id, []))
  directive = _WORKFLOW_CONTINUATION_DIRECTIVES.get(
    resume_step_id,
    "Resume the workflow from the recovered current step using the persisted artifacts as authoritative context.",
  )
  artifact_list = ", ".join(artifact_keys) if artifact_keys else "none"
  spec_summary = str(session_summary.get("spec_summary", "") or "")
  acceptance_criteria_count = session_summary.get("acceptance_criteria_count", 0)
  rollout_relevant = session_summary.get("rollout_relevant", False)
  return (
    "Use `bill-feature-verify` in continuation mode.\n"
    f"Workflow id: {workflow_id}\n"
    f"Session id: {session_id or '(none)'}\n"
    f"Continue status: {continue_status}\n"
    f"Resume step: {resume_step_id} ({_WORKFLOW_STEP_LABELS.get(resume_step_id, resume_step_id)})\n"
    f"Recovered artifacts: {artifact_list}\n"
    f"Acceptance criteria count: {acceptance_criteria_count}\n"
    f"Rollout relevant: {rollout_relevant}\n"
    f"Spec summary: {spec_summary or '(none saved)'}\n"
    f"Reference sections: {references or 'normal step instructions only'}\n"
    "Rules: do not rerun already-completed steps unless the workflow loop explicitly sends work backwards; treat `step_artifacts` as authoritative; keep the same `workflow_id` and `session_id`; after the resumed step, continue the normal sequence defined by `bill-feature-verify`.\n"
    f"Step directive: {directive}\n"
    f"Immediate next action: {next_action}"
  )


def build_workflow_resume_payload(
  connection: sqlite3.Connection,
  workflow_id: str,
) -> dict[str, object]:
  payload = build_workflow_payload(connection, workflow_id)
  if not payload:
    return {}

  workflow_status = str(payload["workflow_status"])
  current_step_id = str(payload["current_step_id"])
  steps = payload["steps"]
  artifacts = payload["artifacts"]
  assert isinstance(steps, list)
  assert isinstance(artifacts, dict)

  steps_by_id = {
    str(step.get("step_id", "")): step
    for step in steps
    if isinstance(step, dict) and step.get("step_id")
  }
  last_completed_step_id = ""
  for step_id in FEATURE_VERIFY_WORKFLOW_STEP_IDS:
    step = steps_by_id.get(step_id)
    if step and step.get("status") == "completed":
      last_completed_step_id = step_id

  resume_step_id = current_step_id
  current_step = steps_by_id.get(current_step_id, {})
  if workflow_status == "completed":
    resume_mode = "done"
  elif workflow_status in FEATURE_VERIFY_WORKFLOW_TERMINAL_STATUSES:
    resume_mode = "recover"
  else:
    resume_mode = "resume"
    if current_step.get("status") == "completed":
      for step_id in FEATURE_VERIFY_WORKFLOW_STEP_IDS:
        step = steps_by_id.get(step_id)
        if step and step.get("status") in {"running", "blocked", "pending"}:
          resume_step_id = step_id
          current_step = step
          break

  available_artifacts = sorted(str(key) for key in artifacts.keys())
  required_artifacts = list(_WORKFLOW_REQUIRED_ARTIFACTS_BY_STEP.get(resume_step_id, []))
  missing_artifacts = [key for key in required_artifacts if key not in artifacts]
  can_resume = resume_mode != "done" and not missing_artifacts
  if resume_mode == "done":
    next_action = "Workflow already completed. Inspect verdict_result or telemetry if you need a summary."
  else:
    next_action = _WORKFLOW_RESUME_ACTIONS.get(
      resume_step_id,
      "Inspect workflow state, refresh missing artifacts, and continue from the current step.",
    )

  payload.update({
    "resume_mode": resume_mode,
    "resume_step_id": resume_step_id,
    "last_completed_step_id": last_completed_step_id,
    "available_artifacts": available_artifacts,
    "required_artifacts": required_artifacts,
    "missing_artifacts": missing_artifacts,
    "can_resume": can_resume,
    "next_action": next_action,
  })
  return payload


def continue_workflow(
  connection: sqlite3.Connection,
  workflow_id: str,
) -> dict[str, object]:
  payload = build_workflow_resume_payload(connection, workflow_id)
  if not payload:
    return {}

  workflow_status_before = str(payload["workflow_status"])
  resume_mode = str(payload["resume_mode"])
  resume_step_id = str(payload["resume_step_id"])
  current_step_id = str(payload["current_step_id"])
  next_action = str(payload["next_action"])
  artifacts = payload["artifacts"]
  steps = payload["steps"]
  can_resume = bool(payload["can_resume"])
  assert isinstance(artifacts, dict)
  assert isinstance(steps, list)

  artifact_keys = _workflow_continue_artifact_keys(
    resume_step_id=resume_step_id,
    artifacts=artifacts,
  )
  step_artifacts = {
    key: artifacts[key]
    for key in artifact_keys
  }
  session_id = str(payload["session_id"] or "")
  session_summary: dict[str, object] = {}
  if session_id:
    session_row = fetch_session(connection, session_id)
    if session_row is not None:
      session_summary = build_started_payload(connection, session_id, "full")

  continue_status = "blocked"
  if resume_mode == "done":
    continue_status = "done"
  elif can_resume:
    steps_by_id = {
      str(step.get("step_id", "")): step
      for step in steps
      if isinstance(step, dict) and step.get("step_id")
    }
    current_step = steps_by_id.get(resume_step_id, {})
    current_attempt_count = int(current_step.get("attempt_count", 0) or 0)
    already_running = (
      workflow_status_before == "running"
      and current_step_id == resume_step_id
      and current_step.get("status") == "running"
    )
    continue_status = "already_running" if already_running else "reopened"
    if not already_running:
      next_attempt_count = max(current_attempt_count + 1, 1)
      save_workflow_state(
        connection,
        workflow_id=workflow_id,
        workflow_status="running",
        current_step_id=resume_step_id,
        step_updates=[
          {
            "step_id": resume_step_id,
            "status": "running",
            "attempt_count": next_attempt_count,
          }
        ],
        artifacts_patch=None,
        session_id=session_id,
      )
      payload = build_workflow_resume_payload(connection, workflow_id)
      if not payload:
        return {}
      artifacts = payload["artifacts"]
      assert isinstance(artifacts, dict)
      step_artifacts = {
        key: artifacts[key]
        for key in artifact_keys
        if key in artifacts
      }

  payload.update({
    "skill_name": "bill-feature-verify",
    "continuation_mode": "resume_existing_workflow",
    "workflow_status_before_continue": workflow_status_before,
    "continue_status": continue_status,
    "continue_step_id": resume_step_id,
    "continue_step_label": _WORKFLOW_STEP_LABELS.get(resume_step_id, resume_step_id),
    "continue_step_directive": _WORKFLOW_CONTINUATION_DIRECTIVES.get(
      resume_step_id,
      "Resume the workflow from the current step using the recovered artifacts as authoritative context.",
    ),
    "reference_sections": list(_WORKFLOW_CONTINUATION_REFERENCE_SECTIONS.get(resume_step_id, [])),
    "step_artifact_keys": artifact_keys,
    "step_artifacts": step_artifacts,
    "session_summary": session_summary,
    "continuation_brief": _build_workflow_continuation_brief(
      workflow_id=workflow_id,
      resume_step_id=resume_step_id,
      continue_status=continue_status,
      next_action=next_action,
      artifact_keys=artifact_keys,
    ),
    "continuation_entry_prompt": _build_workflow_continuation_entry_prompt(
      workflow_id=workflow_id,
      session_id=session_id,
      resume_step_id=resume_step_id,
      continue_status=continue_status,
      artifact_keys=artifact_keys,
      next_action=next_action,
      session_summary=session_summary,
    ),
  })
  return payload
