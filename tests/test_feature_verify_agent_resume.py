from __future__ import annotations

from pathlib import Path
import os
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tests.feature_verify_agent_harness import FeatureVerifyAgentHarness  # noqa: E402
from skill_bill.mcp_server import (  # noqa: E402
  feature_verify_workflow_open,
  feature_verify_workflow_update,
)


class FeatureVerifyAgentResumeTest(unittest.TestCase):

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
    self.harness = FeatureVerifyAgentHarness()

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_interrupt_at_code_review_then_continue_dispatches_feature_verify_from_code_review(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="code_review",
      step_updates=[
        {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
        {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
        {"step_id": "code_review", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "input_context": {"verify_target": "PR-1"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 3},
        "feature_flag_audit_result": {"status": "skipped"},
      },
    )

    resumed = self.harness.continue_feature_verify_workflow(workflow_id)

    payload = resumed["tool_payload"]
    skill_call = resumed["skill_call"]
    assert isinstance(skill_call, dict)
    self.assertEqual(payload["status"], "ok")
    self.assertEqual(payload["continue_step_id"], "code_review")
    self.assertEqual(skill_call["skill_name"], "bill-feature-verify")
    self.assertEqual(skill_call["dispatch_mode"], "resume_existing_workflow")
    self.assertEqual(skill_call["start_step_id"], "code_review")
    self.assertFalse(skill_call["should_restart_from_step1"])
    self.assertIn("SKILL.md :: Step 5: Code Review", skill_call["reference_sections"])
    self.assertEqual(
      skill_call["artifact_keys"],
      ["criteria_summary", "diff_summary", "feature_flag_audit_result", "input_context"],
    )
    self.assertIn("pass orchestrated=true to bill-code-review", skill_call["directive"])
    self.assertIn("do not rerun already-completed steps", skill_call["entry_prompt"])

  def test_interrupt_at_audit_then_continue_dispatches_feature_verify_from_audit(self) -> None:
    opened = feature_verify_workflow_open()
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
        "diff_summary": {"changed_files_count": 3},
        "review_result": {"finding_count": 2},
      },
    )

    resumed = self.harness.continue_feature_verify_workflow(workflow_id)

    payload = resumed["tool_payload"]
    skill_call = resumed["skill_call"]
    assert isinstance(skill_call, dict)
    self.assertEqual(payload["status"], "ok")
    self.assertEqual(payload["continue_step_id"], "completeness_audit")
    self.assertEqual(skill_call["start_step_id"], "completeness_audit")
    self.assertFalse(skill_call["should_restart_from_step1"])
    self.assertIn("SKILL.md :: Step 6: Completeness Audit", skill_call["reference_sections"])
    self.assertEqual(
      skill_call["artifact_keys"],
      ["criteria_summary", "diff_summary", "input_context", "review_result"],
    )
    self.assertIn("Reuse criteria_summary, diff_summary, and review_result", skill_call["directive"])
    self.assertIn("after the resumed step, continue the normal sequence", skill_call["entry_prompt"])

  def test_blocked_continuation_prevents_skill_dispatch(self) -> None:
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

    resumed = self.harness.continue_feature_verify_workflow(workflow_id)

    payload = resumed["tool_payload"]
    self.assertEqual(payload["status"], "error")
    self.assertEqual(payload["continue_status"], "blocked")
    self.assertIn("review_result", payload["missing_artifacts"])
    self.assertIsNone(resumed["skill_call"])

  def test_interrupted_code_review_workflow_can_resume_and_finish_without_restarting(self) -> None:
    opened = feature_verify_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="code_review",
      step_updates=[
        {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
        {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
        {"step_id": "code_review", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "input_context": {"verify_target": "PR-1"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 3},
        "feature_flag_audit_result": {"status": "skipped"},
      },
    )

    resumed = self.harness.continue_and_complete_workflow(workflow_id)

    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(
      resumed["executed_steps"],
      [
        "code_review",
        "completeness_audit",
        "verdict",
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
    self.assertEqual(steps_by_id["finish"]["status"], "completed")
    self.assertIn("verdict_result", artifacts)
    self.assertIn("completeness_audit_result", artifacts)

  def test_interrupted_audit_workflow_can_loop_back_and_finish(self) -> None:
    opened = feature_verify_workflow_open()
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
        "diff_summary": {"changed_files_count": 3},
        "review_result": {"finding_count": 2},
      },
    )

    resumed = self.harness.continue_and_complete_workflow(workflow_id, audit_loops=1)

    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(
      resumed["executed_steps"],
      [
        "completeness_audit",
        "gather_diff",
        "feature_flag_audit",
        "code_review",
        "completeness_audit",
        "verdict",
        "finish",
      ],
    )
    steps = final_workflow["steps"]
    artifacts = final_workflow["artifacts"]
    assert isinstance(steps, list)
    assert isinstance(artifacts, dict)
    steps_by_id = {str(step["step_id"]): step for step in steps if isinstance(step, dict)}
    self.assertEqual(final_workflow["workflow_status"], "completed")
    self.assertEqual(steps_by_id["gather_diff"]["attempt_count"], 2)
    self.assertEqual(steps_by_id["completeness_audit"]["attempt_count"], 3)
    self.assertTrue(artifacts["completeness_audit_result"]["pass"])


if __name__ == "__main__":
  unittest.main()
