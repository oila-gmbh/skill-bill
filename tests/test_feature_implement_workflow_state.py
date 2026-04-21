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

from skill_bill.mcp_server import (
  feature_implement_workflow_continue,
  feature_implement_workflow_get,
  feature_implement_workflow_latest,
  feature_implement_workflow_list,
  feature_implement_workflow_open,
  feature_implement_workflow_resume,
  feature_implement_workflow_update,
)


class FeatureImplementWorkflowStateTest(unittest.TestCase):

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
      "SELECT * FROM feature_implement_workflows WHERE workflow_id = ?",
      (workflow_id,),
    ).fetchone()
    conn.close()
    return row

  def test_open_creates_default_workflow_state(self) -> None:
    result = feature_implement_workflow_open(session_id="fis-20260421-000001-test")
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["workflow_id"], r"^wfl-\d{8}-\d{6}-[a-z0-9]{4}$")
    self.assertEqual(result["workflow_status"], "running")
    self.assertEqual(result["current_step_id"], "assess")
    self.assertEqual(result["session_id"], "fis-20260421-000001-test")
    self.assertEqual(result["steps"][0]["step_id"], "assess")
    self.assertEqual(result["steps"][0]["status"], "running")
    self.assertEqual(result["steps"][0]["attempt_count"], 1)
    self.assertEqual(result["steps"][-1]["step_id"], "finish")
    self.assertEqual(result["artifacts"], {})

    row = self._workflow_row(result["workflow_id"])
    self.assertIsNotNone(row)
    self.assertEqual(row["workflow_status"], "running")

  def test_open_validates_initial_step(self) -> None:
    result = feature_implement_workflow_open(current_step_id="ship_it")
    self.assertEqual(result["status"], "error")
    self.assertIn("current_step_id", result["error"])

  def test_update_merges_steps_and_artifacts(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    result = feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="running",
      current_step_id="plan",
      session_id="fis-20260421-000002-test",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "running", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {
          "feature_name": "workflow pilot",
          "feature_size": "MEDIUM",
        },
      },
    )
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["current_step_id"], "plan")
    self.assertEqual(result["session_id"], "fis-20260421-000002-test")
    self.assertEqual(result["artifacts"]["assessment"]["feature_name"], "workflow pilot")

    steps_by_id = {step["step_id"]: step for step in result["steps"]}
    self.assertEqual(steps_by_id["assess"]["status"], "completed")
    self.assertEqual(steps_by_id["plan"]["status"], "running")
    self.assertEqual(steps_by_id["plan"]["attempt_count"], 1)
    self.assertEqual(steps_by_id["implement"]["status"], "pending")

  def test_get_returns_persisted_state(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="completed",
      current_step_id="finish",
      step_updates=[
        {"step_id": "finish", "status": "completed", "attempt_count": 1},
      ],
      artifacts_patch={"pr_result": {"pr_created": True}},
    )
    result = feature_implement_workflow_get(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["workflow_status"], "completed")
    self.assertEqual(result["current_step_id"], "finish")
    self.assertTrue(result["finished_at"])
    self.assertTrue(result["artifacts"]["pr_result"]["pr_created"])

  def test_list_returns_recent_workflows(self) -> None:
    first = feature_implement_workflow_open()
    second = feature_implement_workflow_open(session_id="fis-20260421-000003-test")
    result = feature_implement_workflow_list(limit=5)
    self.assertEqual(result["status"], "ok")
    self.assertGreaterEqual(result["workflow_count"], 2)
    workflow_ids = [entry["workflow_id"] for entry in result["workflows"]]
    self.assertIn(first["workflow_id"], workflow_ids)
    self.assertIn(second["workflow_id"], workflow_ids)

  def test_latest_returns_most_recent_workflow(self) -> None:
    feature_implement_workflow_open(session_id="fis-20260421-000004-test")
    latest = feature_implement_workflow_open(session_id="fis-20260421-000005-test")
    result = feature_implement_workflow_latest()
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["workflow_id"], latest["workflow_id"])

  def test_latest_errors_when_no_workflows_exist(self) -> None:
    result = feature_implement_workflow_latest()
    self.assertEqual(result["status"], "error")
    self.assertIn("No feature-implement workflows found.", result["error"])

  def test_resume_returns_next_action_for_running_workflow(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="running",
      current_step_id="implement",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "completed", "attempt_count": 1},
        {"step_id": "implement", "status": "running", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "preplan_digest": {"validation_strategy": "bill-quality-check"},
        "plan": {"task_count": 4},
      },
    )
    result = feature_implement_workflow_resume(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["resume_mode"], "resume")
    self.assertEqual(result["resume_step_id"], "implement")
    self.assertTrue(result["can_resume"])
    self.assertIn("Resume implementation", result["next_action"])

  def test_resume_detects_missing_artifacts_for_recovery(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="audit",
      step_updates=[
        {"step_id": "audit", "status": "failed", "attempt_count": 1},
      ],
    )
    result = feature_implement_workflow_resume(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["resume_mode"], "recover")
    self.assertEqual(result["resume_step_id"], "audit")
    self.assertFalse(result["can_resume"])
    self.assertIn("implementation_summary", result["missing_artifacts"])
    self.assertIn("review_result", result["required_artifacts"])

  def test_continue_reopens_failed_workflow_and_returns_handoff(self) -> None:
    opened = feature_implement_workflow_open(session_id="fis-20260421-000006-test")
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="audit",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "completed", "attempt_count": 1},
        {"step_id": "implement", "status": "completed", "attempt_count": 1},
        {"step_id": "review", "status": "completed", "attempt_count": 1},
        {"step_id": "audit", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "implementation_summary": {"files_modified": 3},
        "review_result": {"iteration": 1},
      },
    )
    result = feature_implement_workflow_continue(workflow_id)
    self.assertEqual(result["status"], "ok")
    self.assertEqual(result["continue_status"], "reopened")
    self.assertEqual(result["skill_name"], "bill-feature-implement")
    self.assertEqual(result["continuation_mode"], "resume_existing_workflow")
    self.assertEqual(result["workflow_status_before_continue"], "failed")
    self.assertEqual(result["workflow_status"], "running")
    self.assertEqual(result["current_step_id"], "audit")
    self.assertEqual(result["step_artifact_keys"], ["assessment", "branch", "implementation_summary", "review_result"])
    self.assertIn("SKILL.md :: Continuation Mode", result["reference_sections"])
    self.assertIn("Resume at the completeness audit", result["continue_step_directive"])
    self.assertIsInstance(result["session_summary"], dict)
    self.assertIn("Resume `bill-feature-implement` workflow", result["continuation_brief"])
    self.assertIn("Use `bill-feature-implement` in continuation mode.", result["continuation_entry_prompt"])

    steps_by_id = {step["step_id"]: step for step in result["steps"]}
    self.assertEqual(steps_by_id["audit"]["status"], "running")
    self.assertEqual(steps_by_id["audit"]["attempt_count"], 2)

  def test_continue_errors_when_required_artifacts_are_missing(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="validate",
      step_updates=[
        {"step_id": "validate", "status": "failed", "attempt_count": 1},
      ],
    )
    result = feature_implement_workflow_continue(workflow_id)
    self.assertEqual(result["status"], "error")
    self.assertEqual(result["continue_status"], "blocked")
    self.assertIn("audit_report", result["missing_artifacts"])
    self.assertIn("Cannot continue workflow", result["error"])

  def test_update_unknown_workflow_errors(self) -> None:
    result = feature_implement_workflow_update(
      workflow_id="wfl-20260421-000000-miss",
      workflow_status="running",
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("Unknown workflow_id", result["error"])

  def test_update_validates_step_payload(self) -> None:
    opened = feature_implement_workflow_open()
    result = feature_implement_workflow_update(
      workflow_id=opened["workflow_id"],
      workflow_status="running",
      step_updates=[
        {"step_id": "plan", "status": "launching", "attempt_count": 1},
      ],
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("step_updates.status", result["error"])

  def test_update_validates_artifacts_patch_shape(self) -> None:
    opened = feature_implement_workflow_open()
    result = feature_implement_workflow_update(
      workflow_id=opened["workflow_id"],
      workflow_status="running",
      artifacts_patch=["not", "a", "dict"],
    )
    self.assertEqual(result["status"], "error")
    self.assertIn("artifacts_patch", result["error"])


if __name__ == "__main__":
  unittest.main()
