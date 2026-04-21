from __future__ import annotations

import json
import random
import sqlite3
import string
from datetime import datetime, timezone

from skill_bill.constants import (
  AUDIT_RESULTS,
  BOUNDARY_HISTORY_VALUES,
  COMPLETION_STATUSES,
  EVENT_FEATURE_IMPLEMENT_FINISHED,
  EVENT_FEATURE_IMPLEMENT_STARTED,
  FEATURE_FLAG_PATTERNS,
  FEATURE_SIZES,
  FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
  FEATURE_IMPLEMENT_WORKFLOW_PREFIX,
  FEATURE_IMPLEMENT_WORKFLOW_STATUSES,
  FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS,
  FEATURE_IMPLEMENT_WORKFLOW_STEP_STATUSES,
  FEATURE_IMPLEMENT_WORKFLOW_TERMINAL_STATUSES,
  ISSUE_KEY_TYPES,
  SPEC_INPUT_TYPES,
  VALIDATION_RESULTS,
)
from skill_bill.stats import enqueue_telemetry_event


def generate_feature_session_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"fis-{now:%Y%m%d-%H%M%S}-{suffix}"


def generate_feature_workflow_id() -> str:
  now = datetime.now(timezone.utc)
  suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))
  return f"{FEATURE_IMPLEMENT_WORKFLOW_PREFIX}-{now:%Y%m%d-%H%M%S}-{suffix}"


def validate_enum(value: str, allowed: tuple[str, ...], field_name: str) -> str | None:
  if value not in allowed:
    return f"Invalid {field_name} '{value}'. Allowed: {', '.join(allowed)}"
  return None


def validate_started_params(
  *,
  feature_size: str,
  issue_key_type: str,
  spec_input_types: list[str],
) -> str | None:
  error = validate_enum(feature_size, FEATURE_SIZES, "feature_size")
  if error:
    return error
  error = validate_enum(issue_key_type, ISSUE_KEY_TYPES, "issue_key_type")
  if error:
    return error
  for spec_type in spec_input_types:
    error = validate_enum(spec_type, SPEC_INPUT_TYPES, "spec_input_types")
    if error:
      return error
  return None


def validate_finished_params(
  *,
  completion_status: str,
  feature_flag_pattern: str,
  audit_result: str,
  validation_result: str,
  boundary_history_value: str,
) -> str | None:
  error = validate_enum(completion_status, COMPLETION_STATUSES, "completion_status")
  if error:
    return error
  error = validate_enum(feature_flag_pattern, FEATURE_FLAG_PATTERNS, "feature_flag_pattern")
  if error:
    return error
  error = validate_enum(audit_result, AUDIT_RESULTS, "audit_result")
  if error:
    return error
  error = validate_enum(validation_result, VALIDATION_RESULTS, "validation_result")
  if error:
    return error
  error = validate_enum(boundary_history_value, BOUNDARY_HISTORY_VALUES, "boundary_history_value")
  if error:
    return error
  return None


def _default_workflow_steps(initial_step_id: str) -> list[dict[str, object]]:
  steps: list[dict[str, object]] = []
  for step_id in FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS:
    if step_id == initial_step_id:
      steps.append({
        "step_id": step_id,
        "status": "running",
        "attempt_count": 1,
      })
    else:
      steps.append({
        "step_id": step_id,
        "status": "pending",
        "attempt_count": 0,
      })
  return steps


def validate_workflow_open_params(*, current_step_id: str) -> str | None:
  return validate_enum(current_step_id, FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS, "current_step_id")


def validate_workflow_state_params(
  *,
  workflow_status: str,
  current_step_id: str,
  step_updates: list[dict] | None,
  artifacts_patch: dict | None,
) -> str | None:
  error = validate_enum(workflow_status, FEATURE_IMPLEMENT_WORKFLOW_STATUSES, "workflow_status")
  if error:
    return error
  if current_step_id:
    error = validate_enum(current_step_id, FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS, "current_step_id")
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
      error = validate_enum(step_id, FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS, "step_updates.step_id")
      if error:
        return error
      if step_id in seen_step_ids:
        return f"Duplicate step_id '{step_id}' in step_updates."
      seen_step_ids.add(step_id)
      if not isinstance(status, str) or not status:
        return f"step_updates[{index}].status must be a non-empty string."
      error = validate_enum(status, FEATURE_IMPLEMENT_WORKFLOW_STEP_STATUSES, "step_updates.status")
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
    for step_id in FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS
    if step_id in by_step_id
  ]


def save_started(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  issue_key_provided: bool,
  issue_key_type: str,
  spec_input_types: list[str],
  spec_word_count: int,
  feature_size: str,
  feature_name: str,
  rollout_needed: bool,
  acceptance_criteria_count: int,
  open_questions_count: int,
  spec_summary: str,
) -> None:
  with connection:
    connection.execute(
      """
      INSERT INTO feature_implement_sessions (
        session_id, issue_key_provided, issue_key_type, spec_input_types,
        spec_word_count, feature_size, feature_name, rollout_needed,
        acceptance_criteria_count, open_questions_count, spec_summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      (
        session_id,
        1 if issue_key_provided else 0,
        issue_key_type,
        json.dumps(spec_input_types),
        spec_word_count,
        feature_size,
        feature_name,
        1 if rollout_needed else 0,
        acceptance_criteria_count,
        open_questions_count,
        spec_summary,
      ),
    )


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
      INSERT INTO feature_implement_workflows (
        workflow_id, session_id, workflow_name, contract_version,
        workflow_status, current_step_id, steps_json, artifacts_json
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """,
      (
        workflow_id,
        session_id,
        "bill-feature-implement",
        FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
        "running",
        current_step_id,
        json.dumps(_default_workflow_steps(current_step_id), sort_keys=True),
        json.dumps({}, sort_keys=True),
      ),
    )


def save_finished(
  connection: sqlite3.Connection,
  *,
  session_id: str,
  completion_status: str,
  plan_correction_count: int,
  plan_task_count: int,
  plan_phase_count: int,
  feature_flag_used: bool,
  feature_flag_pattern: str,
  files_created: int,
  files_modified: int,
  tasks_completed: int,
  review_iterations: int,
  audit_result: str,
  audit_iterations: int,
  validation_result: str,
  boundary_history_written: bool,
  boundary_history_value: str,
  pr_created: bool,
  plan_deviation_notes: str,
  child_steps: list[dict] | None = None,
) -> None:
  child_steps_json = json.dumps(list(child_steps or []), sort_keys=True)
  exists = connection.execute(
    "SELECT 1 FROM feature_implement_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()

  if exists:
    with connection:
      connection.execute(
        """
        UPDATE feature_implement_sessions SET
          completion_status = ?,
          plan_correction_count = ?,
          plan_task_count = ?,
          plan_phase_count = ?,
          feature_flag_used = ?,
          feature_flag_pattern = ?,
          files_created = ?,
          files_modified = ?,
          tasks_completed = ?,
          review_iterations = ?,
          audit_result = ?,
          audit_iterations = ?,
          validation_result = ?,
          boundary_history_written = ?,
          boundary_history_value = ?,
          pr_created = ?,
          plan_deviation_notes = ?,
          child_steps_json = ?,
          finished_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (
          completion_status,
          plan_correction_count,
          plan_task_count,
          plan_phase_count,
          1 if feature_flag_used else 0,
          feature_flag_pattern,
          files_created,
          files_modified,
          tasks_completed,
          review_iterations,
          audit_result,
          audit_iterations,
          validation_result,
          1 if boundary_history_written else 0,
          boundary_history_value,
          1 if pr_created else 0,
          plan_deviation_notes,
          child_steps_json,
          session_id,
        ),
      )
  else:
    with connection:
      connection.execute(
        """
        INSERT INTO feature_implement_sessions (
          session_id, completion_status, plan_correction_count,
          plan_task_count, plan_phase_count, feature_flag_used,
          feature_flag_pattern, files_created, files_modified,
          tasks_completed, review_iterations, audit_result,
          audit_iterations, validation_result, boundary_history_written,
          boundary_history_value, pr_created, plan_deviation_notes,
          child_steps_json, finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        (
          session_id,
          completion_status,
          plan_correction_count,
          plan_task_count,
          plan_phase_count,
          1 if feature_flag_used else 0,
          feature_flag_pattern,
          files_created,
          files_modified,
          tasks_completed,
          review_iterations,
          audit_result,
          audit_iterations,
          validation_result,
          1 if boundary_history_written else 0,
          boundary_history_value,
          1 if pr_created else 0,
          plan_deviation_notes,
          child_steps_json,
        ),
      )


def fetch_workflow(connection: sqlite3.Connection, workflow_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_implement_workflows WHERE workflow_id = ?",
    (workflow_id,),
  ).fetchone()


def fetch_latest_workflow(connection: sqlite3.Connection) -> sqlite3.Row | None:
  return connection.execute(
    """
    SELECT *
    FROM feature_implement_workflows
    ORDER BY updated_at DESC, rowid DESC
    LIMIT 1
    """
  ).fetchone()


def list_workflows(connection: sqlite3.Connection, limit: int = 20) -> list[sqlite3.Row]:
  return list(connection.execute(
    """
    SELECT *
    FROM feature_implement_workflows
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
  terminal = workflow_status in FEATURE_IMPLEMENT_WORKFLOW_TERMINAL_STATUSES

  with connection:
    connection.execute(
      """
      UPDATE feature_implement_workflows
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


def fetch_session(connection: sqlite3.Connection, session_id: str) -> sqlite3.Row | None:
  return connection.execute(
    "SELECT * FROM feature_implement_sessions WHERE session_id = ?",
    (session_id,),
  ).fetchone()


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
    "workflow_name": row["workflow_name"] or "bill-feature-implement",
    "contract_version": row["contract_version"] or FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
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
    "workflow_name": row["workflow_name"] or "bill-feature-implement",
    "contract_version": row["contract_version"] or FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
    "workflow_status": row["workflow_status"] or "pending",
    "current_step_id": row["current_step_id"] or "",
    "started_at": row["started_at"] or "",
    "updated_at": row["updated_at"] or "",
    "finished_at": row["finished_at"] or "",
  }


_WORKFLOW_REQUIRED_ARTIFACTS_BY_STEP: dict[str, list[str]] = {
  "assess": [],
  "create_branch": ["assessment"],
  "preplan": ["assessment", "branch"],
  "plan": ["assessment", "preplan_digest"],
  "implement": ["plan", "preplan_digest"],
  "review": ["implementation_summary"],
  "audit": ["implementation_summary", "review_result"],
  "validate": ["audit_report"],
  "write_history": ["implementation_summary", "validation_result"],
  "commit_push": ["implementation_summary", "validation_result", "history_result"],
  "pr_description": ["implementation_summary", "branch"],
  "finish": ["pr_result"],
}


_WORKFLOW_RESUME_ACTIONS: dict[str, str] = {
  "assess": "Reconstruct or confirm the Step 1 assessment, then reopen the workflow from create_branch.",
  "create_branch": "Create or verify the feature branch, persist the branch artifact, then continue to preplan.",
  "preplan": "Re-run the pre-planning phase using the assessment and branch artifacts, then persist preplan_digest.",
  "plan": "Re-run the planning phase using assessment and preplan_digest, then persist the plan artifact.",
  "implement": "Resume implementation from the persisted plan and preplan_digest, then refresh implementation_summary.",
  "review": "Resume code review from the latest implementation_summary and persist review_result after each pass.",
  "audit": "Resume the completeness audit from implementation_summary and review_result, then persist audit_report.",
  "validate": "Resume final validation from the latest audit_report, then persist validation_result.",
  "write_history": "Resume boundary history writing using implementation_summary and validation_result, then persist history_result.",
  "commit_push": "Resume commit/push after verifying implementation_summary, validation_result, and history_result are current.",
  "pr_description": "Resume PR creation using the branch and implementation_summary, then persist pr_result.",
  "finish": "Close the workflow by marking finish completed and setting the final workflow_status.",
}


_WORKFLOW_STEP_LABELS: dict[str, str] = {
  "assess": "Step 1: Collect Design Doc + Assess Size",
  "create_branch": "Step 1b: Create Feature Branch",
  "preplan": "Step 2: Pre-Planning",
  "plan": "Step 3: Create Implementation Plan",
  "implement": "Step 4: Execute Plan",
  "review": "Step 5: Code Review",
  "audit": "Step 6: Completeness Audit",
  "validate": "Step 6b: Quality Check",
  "write_history": "Step 7: Boundary History",
  "commit_push": "Step 8: Commit and Push",
  "pr_description": "Step 9: PR Description",
  "finish": "Finish",
}


_WORKFLOW_CONTINUATION_REFERENCE_SECTIONS: dict[str, list[str]] = {
  "assess": [
    "SKILL.md :: Workflow State",
    "SKILL.md :: Step 1: Collect Design Doc + Assess Size (orchestrator)",
  ],
  "create_branch": [
    "SKILL.md :: Workflow State",
    "SKILL.md :: Step 1b: Create Feature Branch (orchestrator)",
  ],
  "preplan": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 2: Pre-Planning (subagent)",
    "reference.md :: Pre-planning subagent briefing",
  ],
  "plan": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 3: Create Implementation Plan (subagent)",
    "reference.md :: Planning subagent briefing",
  ],
  "implement": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 4: Execute Plan (subagent)",
    "reference.md :: Implementation subagent briefing",
  ],
  "review": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 5: Code Review (orchestrator)",
    "reference.md :: Fix-loop briefing (used by Step 5 review loop)",
  ],
  "audit": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 6: Completeness Audit (subagent)",
    "reference.md :: Completeness audit subagent briefing",
  ],
  "validate": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 6b: Final Validation Gate (subagent)",
    "reference.md :: Quality-check subagent briefing",
  ],
  "write_history": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 7: Write Boundary History (orchestrator)",
  ],
  "commit_push": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 8: Commit and Push (orchestrator)",
  ],
  "pr_description": [
    "SKILL.md :: Continuation Mode",
    "SKILL.md :: Step 9: Generate PR Description (subagent)",
    "reference.md :: PR-description subagent briefing",
  ],
  "finish": [
    "SKILL.md :: Telemetry: Record Finished",
    "reference.md :: Workflow State Contract",
  ],
}


_WORKFLOW_CONTINUATION_DIRECTIVES: dict[str, str] = {
  "assess": "Reconstruct the Step 1 assessment from the saved assessment artifact, confirm it with the user if needed, then reopen the normal flow from create_branch.",
  "create_branch": "Do not rerun Step 1 discovery. Reuse the saved assessment artifact, create or verify the feature branch, persist the branch artifact, then continue into preplan.",
  "preplan": "Skip Steps 1 and 1b. Reuse the saved assessment and branch artifacts as the contract and branch context, then spawn the pre-planning subagent with those recovered inputs.",
  "plan": "Skip the discovery steps. Reuse the saved assessment and preplan_digest artifacts, then spawn the planning subagent from that recovered context.",
  "implement": "Do not re-plan unless the recovered plan proves invalid. Reuse the saved plan and preplan_digest artifacts, then resume the implementation subagent from Step 4.",
  "review": "Do not re-run implementation first unless the review loop sends work back. Start from the latest implementation_summary artifact and run Step 5 inline in the orchestrator.",
  "audit": "Resume at the completeness audit using the latest implementation_summary and review_result artifacts. Only loop back to planning if the audit actually finds gaps.",
  "validate": "Resume the final validation gate from the latest audit_report artifact, then continue the normal finalization sequence without pausing unless validation fails.",
  "write_history": "Skip directly to boundary history writing using the persisted implementation_summary and validation_result artifacts, then continue with commit and PR creation.",
  "commit_push": "Do not revisit earlier steps. Verify the persisted implementation_summary, validation_result, and history_result artifacts are still current, then run commit/push.",
  "pr_description": "Resume directly at PR creation using the saved branch and implementation_summary artifacts, then finish the workflow and telemetry sequence.",
  "finish": "Do not re-execute work. Close the workflow cleanly by inspecting pr_result and final telemetry state, then emit only the terminal summary if anything is still missing.",
}


def _workflow_continue_artifact_keys(
  *,
  resume_step_id: str,
  artifacts: dict[str, object],
) -> list[str]:
  ordered_keys: list[str] = []
  for key in ("assessment", "branch"):
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
    f"Resume `bill-feature-implement` workflow `{workflow_id}` from "
    f"`{step_label}` (`{resume_step_id}`). "
    "Follow the normal step instructions in "
    "`skills/bill-feature-implement/SKILL.md` and "
    "`skills/bill-feature-implement/reference.md`. "
    f"Use the recovered `step_artifacts` in this payload ({artifact_list}) "
    "instead of reconstructing prior context from chat history. "
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
  feature_name: str,
  feature_size: str,
  branch_name: str,
  session_summary: dict[str, object],
) -> str:
  references = "; ".join(_WORKFLOW_CONTINUATION_REFERENCE_SECTIONS.get(resume_step_id, []))
  directive = _WORKFLOW_CONTINUATION_DIRECTIVES.get(
    resume_step_id,
    "Resume the workflow from the recovered current step using the persisted artifacts as authoritative context.",
  )
  artifact_list = ", ".join(artifact_keys) if artifact_keys else "none"
  spec_summary = str(session_summary.get("spec_summary", "") or "")
  return (
    "Use `bill-feature-implement` in continuation mode.\n"
    f"Workflow id: {workflow_id}\n"
    f"Session id: {session_id or '(none)'}\n"
    f"Continue status: {continue_status}\n"
    f"Resume step: {resume_step_id} ({_WORKFLOW_STEP_LABELS.get(resume_step_id, resume_step_id)})\n"
    f"Feature: {feature_name or '(unknown)'}\n"
    f"Feature size: {feature_size or '(unknown)'}\n"
    f"Branch: {branch_name or '(unknown)'}\n"
    f"Recovered artifacts: {artifact_list}\n"
    f"Spec summary: {spec_summary or '(none saved)'}\n"
    f"Reference sections: {references or 'normal step instructions only'}\n"
    "Rules: do not rerun already-completed steps unless the workflow loop explicitly sends work backwards; treat `step_artifacts` as authoritative; keep the same `workflow_id` and `session_id`; after the resumed step, continue the normal sequence defined by `bill-feature-implement`.\n"
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
  for step_id in FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS:
    step = steps_by_id.get(step_id)
    if step and step.get("status") == "completed":
      last_completed_step_id = step_id

  resume_step_id = current_step_id
  current_step = steps_by_id.get(current_step_id, {})
  if workflow_status == "completed":
    resume_mode = "done"
  elif workflow_status in FEATURE_IMPLEMENT_WORKFLOW_TERMINAL_STATUSES:
    resume_mode = "recover"
  else:
    resume_mode = "resume"
    if current_step.get("status") == "completed":
      for step_id in FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS:
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
    next_action = "Workflow already completed. Inspect pr_result or telemetry if you need a summary."
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
  assessment = artifacts.get("assessment")
  branch = artifacts.get("branch")
  assessment_summary = assessment if isinstance(assessment, dict) else {}
  branch_name = ""
  if isinstance(branch, dict):
    branch_name = str(branch.get("branch_name", "")).strip()
  elif isinstance(branch, str):
    branch_name = branch.strip()
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
        session_id=str(payload["session_id"]),
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
      assessment = artifacts.get("assessment")
      branch = artifacts.get("branch")
      assessment_summary = assessment if isinstance(assessment, dict) else {}
      branch_name = ""
      if isinstance(branch, dict):
        branch_name = str(branch.get("branch_name", "")).strip()
      elif isinstance(branch, str):
        branch_name = branch.strip()

  payload.update({
    "skill_name": "bill-feature-implement",
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
    "feature_name": str(assessment_summary.get("feature_name", "") or ""),
    "feature_size": str(assessment_summary.get("feature_size", "") or ""),
    "branch_name": branch_name,
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
      feature_name=str(assessment_summary.get("feature_name", "") or ""),
      feature_size=str(assessment_summary.get("feature_size", "") or ""),
      branch_name=branch_name,
      session_summary=session_summary,
    ),
  })
  return payload


def build_started_payload(
  connection: sqlite3.Connection,
  session_id: str,
  level: str,
) -> dict[str, object]:
  row = fetch_session(connection, session_id)
  if row is None:
    return {}

  payload: dict[str, object] = {
    "session_id": row["session_id"],
    "issue_key_provided": bool(row["issue_key_provided"]),
    "issue_key_type": row["issue_key_type"],
    "spec_input_types": json.loads(row["spec_input_types"] or "[]"),
    "spec_word_count": row["spec_word_count"],
    "feature_size": row["feature_size"],
    "rollout_needed": bool(row["rollout_needed"]),
    "acceptance_criteria_count": row["acceptance_criteria_count"],
    "open_questions_count": row["open_questions_count"],
  }

  if level == "full":
    payload["feature_name"] = row["feature_name"]
    payload["spec_summary"] = row["spec_summary"]

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

  child_steps_raw = row["child_steps_json"] or ""
  try:
    child_steps = json.loads(child_steps_raw) if child_steps_raw else []
    if not isinstance(child_steps, list):
      child_steps = []
  except json.JSONDecodeError:
    child_steps = []

  payload.update({
    "completion_status": row["completion_status"] or "",
    "plan_correction_count": row["plan_correction_count"] or 0,
    "plan_task_count": row["plan_task_count"] or 0,
    "plan_phase_count": row["plan_phase_count"] or 0,
    "feature_flag_used": bool(row["feature_flag_used"]),
    "feature_flag_pattern": row["feature_flag_pattern"] or "none",
    "files_created": row["files_created"] or 0,
    "files_modified": row["files_modified"] or 0,
    "tasks_completed": row["tasks_completed"] or 0,
    "review_iterations": row["review_iterations"] or 0,
    "audit_result": row["audit_result"] or "skipped",
    "audit_iterations": row["audit_iterations"] or 0,
    "validation_result": row["validation_result"] or "skipped",
    "boundary_history_written": bool(row["boundary_history_written"]),
    "boundary_history_value": row["boundary_history_value"] or "none",
    "pr_created": bool(row["pr_created"]),
    "duration_seconds": duration_seconds,
    "child_steps": child_steps,
  })

  if level == "full":
    payload["plan_deviation_notes"] = row["plan_deviation_notes"] or ""

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
      event_name=EVENT_FEATURE_IMPLEMENT_STARTED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_implement_sessions
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
      event_name=EVENT_FEATURE_IMPLEMENT_FINISHED,
      payload=payload,
      enabled=enabled,
    )
    if enabled:
      connection.execute(
        """
        UPDATE feature_implement_sessions
        SET finished_event_emitted_at = CURRENT_TIMESTAMP
        WHERE session_id = ?
        """,
        (session_id,),
      )
