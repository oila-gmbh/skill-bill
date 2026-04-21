from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tests.feature_verify_agent_harness import FeatureVerifyAgentHarness  # noqa: E402
from skill_bill.mcp_server import (  # noqa: E402
  feature_verify_started,
  feature_verify_workflow_open,
  feature_verify_workflow_update,
)


RUN_WORKFLOW_E2E = os.environ.get("SKILL_BILL_RUN_WORKFLOW_E2E") == "1"


@unittest.skipUnless(
  RUN_WORKFLOW_E2E,
  "Set SKILL_BILL_RUN_WORKFLOW_E2E=1 to run subprocess-driven workflow E2E tests.",
)
class FeatureVerifyWorkflowE2ETest(unittest.TestCase):
  """Opt-in end-to-end tests across CLI subprocesses and the agent harness."""

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"enabled": False, "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": self.config_path,
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value
    self.cli_env = os.environ.copy()
    self.harness = FeatureVerifyAgentHarness()

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _run(self, coro):
    return asyncio.run(coro)

  def set_telemetry_enabled(self, enabled: bool) -> None:
    value = "true" if enabled else "false"
    os.environ["SKILL_BILL_TELEMETRY_ENABLED"] = value
    self.cli_env["SKILL_BILL_TELEMETRY_ENABLED"] = value

  def run_cli(self, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
      [sys.executable, "-m", "skill_bill.cli", *args],
      cwd=ROOT,
      env=self.cli_env,
      capture_output=True,
      text=True,
      check=False,
    )

  def _server_params(self) -> StdioServerParameters:
    return StdioServerParameters(
      command=sys.executable,
      args=["-m", "skill_bill.mcp_server"],
      env=self.cli_env,
    )

  def call_mcp_tool(self, tool_name: str, arguments: dict[str, object]) -> dict[str, object]:
    async def run():
      async with stdio_client(self._server_params()) as (read, write):
        async with ClientSession(read, write) as session:
          await session.initialize()
          result = await session.call_tool(tool_name, arguments)
          return json.loads(result.content[0].text)

    return self._run(run())

  def seed_review_interrupted_workflow(self) -> str:
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
    return workflow_id

  def seed_audit_interrupted_workflow(self) -> str:
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
        "input_context": {"verify_target": "PR-2"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 3},
        "review_result": {"finding_count": 1},
      },
    )
    return workflow_id

  def seed_blocked_verdict_workflow(self) -> str:
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
    return workflow_id

  def seed_completed_workflow(self) -> str:
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
    return workflow_id

  def test_cli_list_show_resume_surfaces_interrupted_workflow_state(self) -> None:
    older_id = self.seed_review_interrupted_workflow()
    latest_id = self.seed_audit_interrupted_workflow()

    list_result = self.run_cli("verify-workflow", "list", "--limit", "10", "--format", "json")
    self.assertEqual(list_result.returncode, 0, list_result.stderr)
    list_payload = json.loads(list_result.stdout)
    workflow_ids = [entry["workflow_id"] for entry in list_payload["workflows"]]
    self.assertIn(older_id, workflow_ids)
    self.assertIn(latest_id, workflow_ids)
    self.assertEqual(workflow_ids[0], latest_id)

    show_result = self.run_cli("verify-workflow", "show", latest_id, "--format", "json")
    self.assertEqual(show_result.returncode, 0, show_result.stderr)
    show_payload = json.loads(show_result.stdout)
    self.assertEqual(show_payload["workflow_id"], latest_id)
    self.assertEqual(show_payload["workflow_status"], "failed")
    self.assertEqual(show_payload["current_step_id"], "completeness_audit")
    self.assertIn("review_result", show_payload["artifacts"])

    resume_result = self.run_cli("verify-workflow", "resume", "--latest", "--format", "json")
    self.assertEqual(resume_result.returncode, 0, resume_result.stderr)
    resume_payload = json.loads(resume_result.stdout)
    self.assertEqual(resume_payload["workflow_id"], latest_id)
    self.assertEqual(resume_payload["resume_mode"], "recover")
    self.assertEqual(resume_payload["resume_step_id"], "completeness_audit")
    self.assertTrue(resume_payload["can_resume"])

  def test_cli_continue_latest_can_resume_and_finish_interrupted_workflow(self) -> None:
    feature_verify_workflow_open()
    workflow_id = self.seed_audit_interrupted_workflow()

    continue_result = self.run_cli("verify-workflow", "continue", "--latest", "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["workflow_id"], workflow_id)
    self.assertEqual(continue_payload["continue_step_id"], "completeness_audit")

    finished = self.harness.complete_from_continuation_payload(continue_payload, audit_loops=1)
    final_workflow = finished["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(final_workflow["workflow_status"], "completed")

  def test_cli_continue_completed_workflow_returns_done(self) -> None:
    workflow_id = self.seed_completed_workflow()

    continue_result = self.run_cli("verify-workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    payload = json.loads(continue_result.stdout)
    self.assertEqual(payload["continue_status"], "done")
    self.assertEqual(payload["continue_step_id"], "finish")

  def test_telemetry_backed_workflow_continue_preserves_session_summary(self) -> None:
    self.set_telemetry_enabled(True)
    started = feature_verify_started(
      acceptance_criteria_count=4,
      rollout_relevant=False,
      spec_summary="Verify workflow runtime against a PR diff.",
    )
    self.assertEqual(started["status"], "ok")
    session_id = started["session_id"]
    workflow = feature_verify_workflow_open(session_id=session_id)
    workflow_id = workflow["workflow_id"]
    feature_verify_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="code_review",
      session_id=session_id,
      step_updates=[
        {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
        {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
        {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
        {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
        {"step_id": "code_review", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "input_context": {"verify_target": "PR-3"},
        "criteria_summary": {"acceptance_criteria_count": 4},
        "diff_summary": {"changed_files_count": 3},
        "feature_flag_audit_result": {"status": "skipped"},
      },
    )

    continue_result = self.run_cli("verify-workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["session_id"], session_id)
    self.assertEqual(continue_payload["session_summary"]["acceptance_criteria_count"], 4)
    self.assertEqual(continue_payload["session_summary"]["spec_summary"], "Verify workflow runtime against a PR diff.")
    self.assertEqual(continue_payload["continue_step_id"], "code_review")

  def test_mcp_workflow_tools_cover_open_update_list_latest_get_resume_continue(self) -> None:
    opened = self.call_mcp_tool("feature_verify_workflow_open", {"session_id": "fvr-20260421-stdio"})
    workflow_id = opened["workflow_id"]
    self.assertEqual(opened["status"], "ok")

    updated = self.call_mcp_tool(
      "feature_verify_workflow_update",
      {
        "workflow_id": workflow_id,
        "workflow_status": "failed",
        "current_step_id": "code_review",
        "step_updates": [
          {"step_id": "collect_inputs", "status": "completed", "attempt_count": 1},
          {"step_id": "extract_criteria", "status": "completed", "attempt_count": 1},
          {"step_id": "gather_diff", "status": "completed", "attempt_count": 1},
          {"step_id": "feature_flag_audit", "status": "skipped", "attempt_count": 1},
          {"step_id": "code_review", "status": "failed", "attempt_count": 1},
        ],
        "artifacts_patch": {
          "input_context": {"verify_target": "PR-4"},
          "criteria_summary": {"acceptance_criteria_count": 4},
          "diff_summary": {"changed_files_count": 3},
          "feature_flag_audit_result": {"status": "skipped"},
        },
      },
    )
    self.assertEqual(updated["status"], "ok")

    listed = self.call_mcp_tool("feature_verify_workflow_list", {"limit": 10})
    self.assertEqual(listed["status"], "ok")
    self.assertIn(workflow_id, [entry["workflow_id"] for entry in listed["workflows"]])

    latest = self.call_mcp_tool("feature_verify_workflow_latest", {})
    self.assertEqual(latest["status"], "ok")
    self.assertEqual(latest["workflow_id"], workflow_id)

    got = self.call_mcp_tool("feature_verify_workflow_get", {"workflow_id": workflow_id})
    self.assertEqual(got["status"], "ok")
    self.assertEqual(got["current_step_id"], "code_review")

    resumed = self.call_mcp_tool("feature_verify_workflow_resume", {"workflow_id": workflow_id})
    self.assertEqual(resumed["status"], "ok")
    self.assertEqual(resumed["resume_step_id"], "code_review")
    self.assertTrue(resumed["can_resume"])

    continued = self.call_mcp_tool("feature_verify_workflow_continue", {"workflow_id": workflow_id})
    self.assertEqual(continued["status"], "ok")
    self.assertEqual(continued["continue_step_id"], "code_review")

    finished = self.harness.complete_from_continuation_payload(continued)
    final_workflow = finished["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(final_workflow["workflow_status"], "completed")

  def test_mcp_workflow_continue_blocked_surfaces_missing_artifacts(self) -> None:
    opened = self.call_mcp_tool("feature_verify_workflow_open", {})
    workflow_id = opened["workflow_id"]
    self.call_mcp_tool(
      "feature_verify_workflow_update",
      {
        "workflow_id": workflow_id,
        "workflow_status": "failed",
        "current_step_id": "verdict",
        "step_updates": [
          {"step_id": "verdict", "status": "failed", "attempt_count": 1},
        ],
      },
    )

    continued = self.call_mcp_tool("feature_verify_workflow_continue", {"workflow_id": workflow_id})
    self.assertEqual(continued["status"], "error")
    self.assertEqual(continued["continue_status"], "blocked")
    self.assertIn("review_result", continued["missing_artifacts"])


if __name__ == "__main__":
  unittest.main()
