from __future__ import annotations

from pathlib import Path
import os
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tests.feature_implement_agent_harness import FeatureImplementAgentHarness  # noqa: E402
from skill_bill.mcp_server import (  # noqa: E402
  feature_implement_workflow_open,
  feature_implement_workflow_update,
)


class FeatureImplementAgentResumeTest(unittest.TestCase):

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
    self.harness = FeatureImplementAgentHarness()

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_interrupt_at_plan_then_continue_dispatches_feature_implement_from_plan(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="plan",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "preplan_digest": {"validation_strategy": "bill-quality-check"},
      },
    )

    resumed = self.harness.continue_feature_implement_workflow(workflow_id)

    payload = resumed["tool_payload"]
    skill_call = resumed["skill_call"]
    assert isinstance(skill_call, dict)
    self.assertEqual(payload["status"], "ok")
    self.assertEqual(payload["continue_step_id"], "plan")
    self.assertEqual(skill_call["skill_name"], "bill-feature-implement")
    self.assertEqual(skill_call["dispatch_mode"], "resume_existing_workflow")
    self.assertEqual(skill_call["start_step_id"], "plan")
    self.assertFalse(skill_call["should_restart_from_step1"])
    self.assertIn("SKILL.md :: Step 3: Create Implementation Plan (subagent)", skill_call["reference_sections"])
    self.assertIn("reference.md :: Planning subagent briefing", skill_call["reference_sections"])
    self.assertEqual(skill_call["artifact_keys"], ["assessment", "branch", "preplan_digest"])
    self.assertIn("Reuse the saved assessment and preplan_digest artifacts", skill_call["directive"])
    self.assertIn("do not rerun already-completed steps", skill_call["entry_prompt"])

  def test_interrupt_at_audit_then_continue_dispatches_feature_implement_from_audit(self) -> None:
    opened = feature_implement_workflow_open()
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
        "implementation_summary": {"files_modified": 4},
        "review_result": {"iteration_count": 1},
      },
    )

    resumed = self.harness.continue_feature_implement_workflow(workflow_id)

    payload = resumed["tool_payload"]
    skill_call = resumed["skill_call"]
    assert isinstance(skill_call, dict)
    self.assertEqual(payload["status"], "ok")
    self.assertEqual(payload["continue_step_id"], "audit")
    self.assertEqual(skill_call["start_step_id"], "audit")
    self.assertFalse(skill_call["should_restart_from_step1"])
    self.assertIn("SKILL.md :: Step 6: Completeness Audit (subagent)", skill_call["reference_sections"])
    self.assertIn("reference.md :: Completeness audit subagent briefing", skill_call["reference_sections"])
    self.assertEqual(
      skill_call["artifact_keys"],
      ["assessment", "branch", "implementation_summary", "review_result"],
    )
    self.assertIn("Resume at the completeness audit", skill_call["directive"])
    self.assertIn("after the resumed step, continue the normal sequence", skill_call["entry_prompt"])

  def test_blocked_continuation_prevents_skill_dispatch(self) -> None:
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

    resumed = self.harness.continue_feature_implement_workflow(workflow_id)

    payload = resumed["tool_payload"]
    self.assertEqual(payload["status"], "error")
    self.assertEqual(payload["continue_status"], "blocked")
    self.assertIn("audit_report", payload["missing_artifacts"])
    self.assertIsNone(resumed["skill_call"])

  def test_interrupted_plan_workflow_can_resume_and_finish_without_restarting(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="plan",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "preplan_digest": {"validation_strategy": "bill-quality-check"},
      },
    )

    resumed = self.harness.continue_and_complete_workflow(workflow_id)

    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(
      resumed["executed_steps"],
      [
        "plan",
        "implement",
        "review",
        "audit",
        "validate",
        "write_history",
        "commit_push",
        "pr_description",
        "finish",
      ],
    )
    self.assertEqual(final_workflow["status"], "ok")
    self.assertEqual(final_workflow["workflow_status"], "completed")
    self.assertEqual(final_workflow["current_step_id"], "finish")
    steps = final_workflow["steps"]
    artifacts = final_workflow["artifacts"]
    assert isinstance(steps, list)
    assert isinstance(artifacts, dict)
    steps_by_id = {str(step["step_id"]): step for step in steps if isinstance(step, dict)}
    self.assertEqual(steps_by_id["assess"]["attempt_count"], 1)
    self.assertEqual(steps_by_id["create_branch"]["attempt_count"], 1)
    self.assertEqual(steps_by_id["preplan"]["attempt_count"], 1)
    self.assertEqual(steps_by_id["finish"]["status"], "completed")
    self.assertIn("pr_result", artifacts)
    self.assertIn("validation_result", artifacts)
    self.assertIn("history_result", artifacts)

  def test_interrupted_review_workflow_can_loop_back_and_finish(self) -> None:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="review",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "completed", "attempt_count": 1},
        {"step_id": "implement", "status": "completed", "attempt_count": 1},
        {"step_id": "review", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "implementation_summary": {"files_modified": 2},
      },
    )

    resumed = self.harness.continue_and_complete_workflow(workflow_id, review_loops=1)

    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(
      resumed["executed_steps"],
      [
        "review",
        "implement",
        "review",
        "audit",
        "validate",
        "write_history",
        "commit_push",
        "pr_description",
        "finish",
      ],
    )
    steps = final_workflow["steps"]
    artifacts = final_workflow["artifacts"]
    assert isinstance(steps, list)
    assert isinstance(artifacts, dict)
    steps_by_id = {str(step["step_id"]): step for step in steps if isinstance(step, dict)}
    self.assertEqual(final_workflow["workflow_status"], "completed")
    self.assertEqual(steps_by_id["implement"]["attempt_count"], 2)
    self.assertEqual(steps_by_id["review"]["attempt_count"], 3)
    self.assertEqual(artifacts["review_result"]["status"], "pass")


if __name__ == "__main__":
  unittest.main()
