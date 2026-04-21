from __future__ import annotations

from pathlib import Path
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.mcp_server import (  # noqa: E402
  feature_verify_workflow_continue,
  feature_verify_workflow_get,
  feature_verify_workflow_latest,
  feature_verify_workflow_list,
  feature_verify_workflow_open,
  feature_verify_workflow_resume,
  feature_verify_workflow_update,
)


class FeatureVerifyWorkflowStateTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _workflow_row(self, workflow_id: str) -> sqlite3.Row | None:
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
      "SELECT * FROM feature_verify_workflows WHERE workflow_id = ?",
      (workflow_id,),
    ).fetchone()
    conn.close()
    return row

  def test_open_creates_default_workflow_state(self) -> None:
    result = feature_verify_workflow_open(session_id="fvr-20260421-000001-test")
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["workflow_id"], r"^wfv-\d{8}-\d{6}-[a-z0-9]{4}$")
    self.assertEqual(result["workflow_status"], "running")
    self.assertEqual(result["current_step_id"], "gather_diff")
    self.assertEqual(result["session_id"], "fvr-20260421-000001-test")
    steps_by_id = {step["step_id"]: step for step in result["steps"]}
    self.assertEqual(steps_by_id["collect_inputs"]["status"], "completed")
    self.assertEqual(steps_by_id["extract_criteria"]["status"], "completed")
    self.assertEqual(steps_by_id["gather_diff"]["status"], "running")
    self.assertEqual(steps_by_id["finish"]["step_id"], "finish")
    self.assertEqual(result["artifacts"], {})

    row = self._workflow_row(result["workflow_id"])
    self.assertIsNotNone(row)
    self.assertEqual(row["workflow_status"], "running")

  def test_open_validates_initial_step(self) -> None:
    result = feature_verify_workflow_open(current_step_id="ship_it")
    self.assertEqual(result["status"], "error")
    self.assertIn("current_step_id", result["error"])

  def test_update_merges_steps_and_artifacts(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    result = feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="running",
      current_step_id="code_review",
      session_id="fvr-20260421-000002-test",
      step_updates=[
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "code_review", "status": "running", "attempt_count": 1},
      ],
      artifacts_patch={
        "diff_summary": {
          "verify_target": "PR-1",
          "changed_files_count": 4,
        },
      },
    )
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["current_step_id"], "code_review")
    self.assertEqual(result["session_id"], "fvr-20260421-000002-test")
    self.assertEqual(result["artifacts"]["diff_summary"]["verify_target"], "PR-1")

    steps_by_id = {step["step_id"]: step for step in result["steps"]}
    self.assertEqual(steps_by_id["gather_diff"]["status"], "completed")
    self.assertEqual(steps_by_id["code_review"]["status"], "running")
    self.assertEqual(steps_by_id["code_review"]["attempt_count"], 1)

  def test_get_returns_persisted_state(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="completed",
      current_step_id="finish",
      step_updates=[
        {"step_id": "finish", "status": "completed", "attempt_count": 1},
      ],
      artifacts_patch={"verdict_result": {"recommendation": "APPROVE"}},
    )
    result = feature_verify_workflow_get(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["workflow_status"], "completed")
    self.assertEqual(result["current_step_id"], "finish")
    self.assertTrue(result["finished_at"])
    self.assertEqual(result["artifacts"]["verdict_result"]["recommendation"], "APPROVE")

  def test_list_returns_recent_workflows(self) -> None:
    first = feature_verify_workflow_open()
    second = feature_verify_workflow_open(session_id="fvr-20260421-000003-test")
    result = feature_verify_workflow_list(limit=5)
    self.assertEqual(result["status"], "ok")
    self.assertGreaterEqual(result["workflow_count"], 2)
    workflow_ids = [entry["workflow_id"] for entry in result["workflows"]]
    self.assertIn(first["workflow_id"], workflow_ids)
    self.assertIn(second["workflow_id"], workflow_ids)

  def test_latest_returns_most_recent_workflow(self) -> None:
    feature_verify_workflow_open(session_id="fvr-20260421-000004-test")
    latest = feature_verify_workflow_open(session_id="fvr-20260421-000005-test")
    result = feature_verify_workflow_latest()
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["workflow_id"], latest["workflow_id"])

  def test_latest_errors_when_no_workflows_exist(self) -> None:
    result = feature_verify_workflow_latest()
    self.assertEqual(result["status"], "error")
    self.assertIn("No feature-verify workflows found.", result["error"])

  def test_resume_returns_next_action_for_running_workflow(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="running",
      current_step_id="code_review",
      step_updates=[
        {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
        {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
        {"step_id": "code_review", "status": "running", "attempt_count": 1},
      ],
      artifacts_patch={
        "input_context": {"verify_target": "PR-1"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 4},
        "feature_flag_audit_result": {"status": "skipped"},
      },
    )
    result = feature_verify_workflow_resume(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["resume_mode"], "resume")
    self.assertEqual(result["resume_step_id"], "code_review")
    self.assertTrue(result["can_resume"])
    self.assertIn("invoke bill-code-review", result["next_action"])

  def test_resume_detects_missing_artifacts_for_recovery(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="verdict",
      step_updates=[
        {"step_id": "verdict", "status": "failed", "attempt_count": 1},
      ],
    )
    result = feature_verify_workflow_resume(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["resume_mode"], "recover")
    self.assertEqual(result["resume_step_id"], "verdict")
    self.assertFalse(result["can_resume"])
    self.assertIn("review_result", result["missing_artifacts"])
    self.assertIn("completeness_audit_result", result["required_artifacts"])

  def test_continue_reopens_failed_workflow_and_returns_handoff(self) -> None:
    opened = feature_verify_workflow_open(session_id="fvr-20260421-000006-test")
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="completeness_audit",
      step_updates=[
        {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
        {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
        {"step_id": "code_review", "status": "completed", "attempt_count": 1},
        {"step_id": "completeness_audit", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "input_context": {"verify_target": "PR-1"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 4},
        "review_result": {"finding_count": 2},
      },
    )
    result = feature_verify_workflow_continue(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["continue_status"], "reopened")
    self.assertEqual(result["skill_name"], "bill-feature-verify")
    self.assertEqual(result["continuation_mode"], "resume_existing_workflow")
    self.assertEqual(result["workflow_status_before_continue"], "failed")
    self.assertEqual(result["workflow_status"], "running")
    self.assertEqual(result["current_step_id"], "completeness_audit")
    self.assertEqual(
      result["step_artifact_keys"],
      ["input_context", "criteria_summary", "diff_summary", "review_result"],
    )
    self.assertIn("SKILL.md :: Continuation Mode", result["reference_sections"])
    self.assertIn("Reuse criteria_summary, diff_summary, and review_result", result["continue_step_directive"])
    self.assertIsInstance(result["session_summary"], dict)
    self.assertIn("Resume `bill-feature-verify` workflow", result["continuation_brief"])
    self.assertIn("Use `bill-feature-verify` in continuation mode.", result["continuation_entry_prompt"])

    steps_by_id = {step["step_id"]: step for step in result["steps"]}
    self.assertEqual(steps_by_id["completeness_audit"]["status"], "running")
    self.assertEqual(steps_by_id["completeness_audit"]["attempt_count"], 2)

  def test_continue_returns_done_for_completed_workflow(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="completed",
      current_step_id="finish",
      step_updates=[
        {"step_id": "finish", "status": "completed", "attempt_count": 1},
      ],
      artifacts_patch={"verdict_result": {"recommendation": "APPROVE"}},
    )
    result = feature_verify_workflow_continue(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["continue_status"], "done")
    self.assertEqual(result["continue_step_id"], "finish")

  def test_continue_errors_when_required_artifacts_are_missing(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="verdict",
      step_updates=[
        {"step_id": "verdict", "status": "failed", "attempt_count": 1},
      ],
    )
    result = feature_verify_workflow_continue(workflow_id)
    self.assertEqual(result["status"], "error")
    self.assertEqual(result["continue_status"], "blocked")
    self.assertIn("review_result", result["missing_artifacts"])
    self.assertIn("Cannot continue workflow", result["error"])

  def test_update_unknown_workflow_errors(self) -> None:
    result = feature_verify_workflow_update(
      workflow_id="wfv-20260421-000000-miss",
      workflow_status="running",
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("Unknown workflow_id", result["error"])

  def test_update_validates_step_payload(self) -> None:
    opened = feature_verify_workflow_open()
    result = feature_verify_workflow_update(
      workflow_id=opened["workflow_id"],
      workflow_status="running",
      step_updates=[
        {"step_id": "verdict", "status": "launching", "attempt_count": 1},
      ],
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("step_updates.status", result["error"])

  def test_update_validates_artifacts_patch_shape(self) -> None:
    opened = feature_verify_workflow_open()
    result = feature_verify_workflow_update(
      workflow_id=opened["workflow_id"],
      workflow_status="running",
      artifacts_patch=["not", "a", "dict"],
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("artifacts_patch", result["error"])


if __name__ == "__main__":
  unittest.main()
