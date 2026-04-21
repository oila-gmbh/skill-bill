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

from tests.feature_implement_agent_harness import FeatureImplementAgentHarness  # noqa: E402
from skill_bill.mcp_server import (  # noqa: E402
  feature_implement_started,
  feature_implement_workflow_open,
  feature_implement_workflow_update,
)


RUN_WORKFLOW_E2E = os.environ.get("SKILL_BILL_RUN_WORKFLOW_E2E") == "1"


@unittest.skipUnless(
  RUN_WORKFLOW_E2E,
  "Set SKILL_BILL_RUN_WORKFLOW_E2E=1 to run subprocess-driven workflow E2E tests.",
)
class FeatureImplementWorkflowE2ETest(unittest.TestCase):
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
    self.harness = FeatureImplementAgentHarness()

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

  def seed_plan_interrupted_workflow(self) -> str:
    latest = feature_implement_workflow_open()
    workflow_id = latest["workflow_id"]
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
    return workflow_id

  def seed_review_interrupted_workflow(self) -> str:
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
    return workflow_id

  def seed_blocked_validate_workflow(self) -> str:
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
    return workflow_id

  def seed_abandoned_workflow(self) -> str:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="abandoned",
      current_step_id="audit",
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "completed", "attempt_count": 1},
        {"step_id": "implement", "status": "completed", "attempt_count": 1},
        {"step_id": "review", "status": "completed", "attempt_count": 1},
        {"step_id": "audit", "status": "blocked", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
        "implementation_summary": {"files_modified": 3},
        "review_result": {"iteration_count": 1},
        "terminal_note": "user abandoned during audit",
      },
    )
    return workflow_id

  def seed_completed_workflow(self) -> str:
    opened = feature_implement_workflow_open()
    workflow_id = opened["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="completed",
      current_step_id="finish",
      step_updates=[
        {"step_id": "finish", "status": "completed", "attempt_count": 1},
      ],
      artifacts_patch={"pr_result": {"pr_created": True, "pr_url": "https://example.test/pr/1"}},
    )
    return workflow_id

  def seed_audit_interrupted_workflow(self) -> str:
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
        "review_result": {"iteration_count": 1, "status": "pass"},
      },
    )
    return workflow_id

  def test_cli_list_show_resume_surfaces_interrupted_workflow_state(self) -> None:
    older_id = self.seed_review_interrupted_workflow()
    latest_id = self.seed_plan_interrupted_workflow()

    list_result = self.run_cli("workflow", "list", "--limit", "10", "--format", "json")
    self.assertEqual(list_result.returncode, 0, list_result.stderr)
    list_payload = json.loads(list_result.stdout)
    workflow_ids = [entry["workflow_id"] for entry in list_payload["workflows"]]
    self.assertIn(older_id, workflow_ids)
    self.assertIn(latest_id, workflow_ids)
    self.assertEqual(workflow_ids[0], latest_id)

    show_result = self.run_cli("workflow", "show", latest_id, "--format", "json")
    self.assertEqual(show_result.returncode, 0, show_result.stderr)
    show_payload = json.loads(show_result.stdout)
    self.assertEqual(show_payload["workflow_id"], latest_id)
    self.assertEqual(show_payload["workflow_status"], "failed")
    self.assertEqual(show_payload["current_step_id"], "plan")
    self.assertIn("preplan_digest", show_payload["artifacts"])

    resume_result = self.run_cli("workflow", "resume", "--latest", "--format", "json")
    self.assertEqual(resume_result.returncode, 0, resume_result.stderr)
    resume_payload = json.loads(resume_result.stdout)
    self.assertEqual(resume_payload["workflow_id"], latest_id)
    self.assertEqual(resume_payload["resume_mode"], "recover")
    self.assertEqual(resume_payload["resume_step_id"], "plan")
    self.assertTrue(resume_payload["can_resume"])
    self.assertIn("preplan_digest", resume_payload["available_artifacts"])

  def test_cli_continue_latest_can_resume_and_finish_interrupted_plan_workflow(self) -> None:
    feature_implement_workflow_open()
    workflow_id = self.seed_plan_interrupted_workflow()

    continue_result = self.run_cli("workflow", "continue", "--latest", "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["workflow_id"], workflow_id)
    self.assertEqual(continue_payload["continue_step_id"], "plan")

    resumed = self.harness.complete_from_continuation_payload(continue_payload)
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

    show_result = self.run_cli("workflow", "show", "--latest", "--format", "json")
    self.assertEqual(show_result.returncode, 0, show_result.stderr)
    show_payload = json.loads(show_result.stdout)
    self.assertEqual(show_payload["workflow_id"], workflow_id)
    self.assertEqual(show_payload["workflow_status"], "completed")
    self.assertEqual(show_payload["current_step_id"], "finish")
    self.assertTrue(show_payload["finished_at"])

  def test_cli_continue_explicit_id_can_resume_review_loop_and_finish(self) -> None:
    workflow_id = self.seed_review_interrupted_workflow()

    continue_result = self.run_cli("workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["continue_step_id"], "review")

    resumed = self.harness.complete_from_continuation_payload(
      continue_payload,
      review_loops=1,
    )
    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)

    show_result = self.run_cli("workflow", "show", workflow_id, "--format", "json")
    self.assertEqual(show_result.returncode, 0, show_result.stderr)
    show_payload = json.loads(show_result.stdout)
    steps = show_payload["steps"]
    assert isinstance(steps, list)
    steps_by_id = {
      str(step["step_id"]): step
      for step in steps
      if isinstance(step, dict)
    }
    self.assertEqual(show_payload["workflow_status"], "completed")
    self.assertEqual(steps_by_id["implement"]["attempt_count"], 2)
    self.assertEqual(steps_by_id["review"]["attempt_count"], 3)

  def test_cli_continue_done_state_returns_terminal_summary_without_reopening(self) -> None:
    workflow_id = self.seed_completed_workflow()

    continue_result = self.run_cli("workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["continue_status"], "done")
    self.assertEqual(continue_payload["workflow_status"], "completed")
    self.assertEqual(continue_payload["current_step_id"], "finish")
    self.assertIn("Workflow already completed", continue_payload["next_action"])

    show_result = self.run_cli("workflow", "show", workflow_id, "--format", "json")
    self.assertEqual(show_result.returncode, 0, show_result.stderr)
    show_payload = json.loads(show_result.stdout)
    self.assertEqual(show_payload["workflow_status"], "completed")
    self.assertEqual(show_payload["current_step_id"], "finish")

  def test_cli_continue_blocked_and_resume_done_surface_terminal_states(self) -> None:
    blocked_id = self.seed_blocked_validate_workflow()
    completed_id = self.seed_completed_workflow()

    blocked_continue = self.run_cli("workflow", "continue", blocked_id, "--format", "json")
    self.assertEqual(blocked_continue.returncode, 1)
    blocked_payload = json.loads(blocked_continue.stdout)
    self.assertEqual(blocked_payload["status"], "error")
    self.assertEqual(blocked_payload["continue_status"], "blocked")
    self.assertIn("audit_report", blocked_payload["missing_artifacts"])

    done_resume = self.run_cli("workflow", "resume", completed_id, "--format", "json")
    self.assertEqual(done_resume.returncode, 0, done_resume.stderr)
    done_payload = json.loads(done_resume.stdout)
    self.assertEqual(done_payload["resume_mode"], "done")
    self.assertFalse(done_payload["can_resume"])
    self.assertIn("Workflow already completed", done_payload["next_action"])

  def test_cli_continue_abandoned_workflow_recovers_and_finishes(self) -> None:
    workflow_id = self.seed_abandoned_workflow()

    resume_result = self.run_cli("workflow", "resume", workflow_id, "--format", "json")
    self.assertEqual(resume_result.returncode, 0, resume_result.stderr)
    resume_payload = json.loads(resume_result.stdout)
    self.assertEqual(resume_payload["resume_mode"], "recover")
    self.assertEqual(resume_payload["resume_step_id"], "audit")
    self.assertTrue(resume_payload["can_resume"])

    continue_result = self.run_cli("workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["continue_status"], "reopened")
    self.assertEqual(continue_payload["continue_step_id"], "audit")

    resumed = self.harness.complete_from_continuation_payload(continue_payload)
    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(final_workflow["workflow_status"], "completed")

  def test_cli_continue_audit_loop_can_replan_and_finish(self) -> None:
    workflow_id = self.seed_audit_interrupted_workflow()

    continue_result = self.run_cli("workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["continue_step_id"], "audit")

    resumed = self.harness.complete_from_continuation_payload(
      continue_payload,
      audit_loops=1,
    )
    final_workflow = resumed["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(
      resumed["executed_steps"],
      [
        "audit",
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
    steps = final_workflow["steps"]
    assert isinstance(steps, list)
    steps_by_id = {str(step["step_id"]): step for step in steps if isinstance(step, dict)}
    self.assertEqual(final_workflow["workflow_status"], "completed")
    self.assertEqual(steps_by_id["plan"]["attempt_count"], 2)
    self.assertEqual(steps_by_id["audit"]["attempt_count"], 3)

  def test_cli_and_mcp_latest_ordering_remain_in_sync(self) -> None:
    first_id = self.seed_review_interrupted_workflow()
    second_id = self.seed_plan_interrupted_workflow()
    third_id = self.seed_audit_interrupted_workflow()

    cli_list = self.run_cli("workflow", "list", "--limit", "10", "--format", "json")
    self.assertEqual(cli_list.returncode, 0, cli_list.stderr)
    cli_payload = json.loads(cli_list.stdout)
    cli_ids = [entry["workflow_id"] for entry in cli_payload["workflows"][:3]]
    self.assertEqual(cli_ids[0], third_id)
    self.assertIn(first_id, cli_ids)
    self.assertIn(second_id, cli_ids)

    cli_resume_latest = self.run_cli("workflow", "resume", "--latest", "--format", "json")
    self.assertEqual(cli_resume_latest.returncode, 0, cli_resume_latest.stderr)
    cli_resume_payload = json.loads(cli_resume_latest.stdout)
    self.assertEqual(cli_resume_payload["workflow_id"], third_id)

    mcp_list = self.call_mcp_tool("feature_implement_workflow_list", {"limit": 10})
    self.assertEqual(mcp_list["status"], "ok")
    mcp_ids = [entry["workflow_id"] for entry in mcp_list["workflows"][:3]]
    self.assertEqual(mcp_ids[0], third_id)
    self.assertEqual(mcp_ids[:3], cli_ids)

    mcp_latest = self.call_mcp_tool("feature_implement_workflow_latest", {})
    self.assertEqual(mcp_latest["status"], "ok")
    self.assertEqual(mcp_latest["workflow_id"], third_id)

  def test_telemetry_backed_workflow_continue_preserves_session_summary(self) -> None:
    self.set_telemetry_enabled(True)
    started = feature_implement_started(
      feature_size="MEDIUM",
      acceptance_criteria_count=3,
      open_questions_count=0,
      spec_input_types=["raw_text"],
      spec_word_count=120,
      rollout_needed=False,
      feature_name="workflow pilot",
      issue_key="SKILL-23",
      issue_key_type="jira",
      spec_summary="Adds workflow continuation support.",
    )
    self.assertEqual(started["status"], "ok")
    session_id = started["session_id"]
    workflow = feature_implement_workflow_open(session_id=session_id)
    workflow_id = workflow["workflow_id"]
    feature_implement_workflow_update(
      workflow_id=workflow_id,
      workflow_status="failed",
      current_step_id="plan",
      session_id=session_id,
      step_updates=[
        {"step_id": "assess", "status": "completed", "attempt_count": 1},
        {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
        {"step_id": "preplan", "status": "completed", "attempt_count": 1},
        {"step_id": "plan", "status": "failed", "attempt_count": 1},
      ],
      artifacts_patch={
        "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
        "branch": {"branch_name": "feat/SKILL-23-workflow-pilot"},
        "preplan_digest": {"validation_strategy": "bill-quality-check"},
      },
    )

    continue_result = self.run_cli("workflow", "continue", workflow_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["session_id"], session_id)
    self.assertEqual(continue_payload["feature_name"], "workflow pilot")
    self.assertEqual(continue_payload["session_summary"]["feature_name"], "workflow pilot")
    self.assertEqual(continue_payload["session_summary"]["feature_size"], "MEDIUM")
    self.assertEqual(continue_payload["session_summary"]["spec_summary"], "Adds workflow continuation support.")
    self.assertEqual(continue_payload["continue_step_id"], "plan")

  def test_mcp_workflow_tools_cover_open_update_list_latest_get_resume_continue(self) -> None:
    opened = self.call_mcp_tool("feature_implement_workflow_open", {"session_id": "fis-20260421-stdio"})
    workflow_id = opened["workflow_id"]
    self.assertEqual(opened["status"], "ok")

    updated = self.call_mcp_tool(
      "feature_implement_workflow_update",
      {
        "workflow_id": workflow_id,
        "workflow_status": "failed",
        "current_step_id": "plan",
        "step_updates": [
          {"step_id": "assess", "status": "completed", "attempt_count": 1},
          {"step_id": "create_branch", "status": "completed", "attempt_count": 1},
          {"step_id": "preplan", "status": "completed", "attempt_count": 1},
          {"step_id": "plan", "status": "failed", "attempt_count": 1},
        ],
        "artifacts_patch": {
          "assessment": {"feature_name": "workflow pilot", "feature_size": "MEDIUM"},
          "branch": {"branch_name": "feat/SKILL-1-workflow-pilot"},
          "preplan_digest": {"validation_strategy": "bill-quality-check"},
        },
      },
    )
    self.assertEqual(updated["status"], "ok")

    listed = self.call_mcp_tool("feature_implement_workflow_list", {"limit": 10})
    self.assertEqual(listed["status"], "ok")
    self.assertIn(workflow_id, [entry["workflow_id"] for entry in listed["workflows"]])

    latest = self.call_mcp_tool("feature_implement_workflow_latest", {})
    self.assertEqual(latest["status"], "ok")
    self.assertEqual(latest["workflow_id"], workflow_id)

    got = self.call_mcp_tool("feature_implement_workflow_get", {"workflow_id": workflow_id})
    self.assertEqual(got["status"], "ok")
    self.assertEqual(got["current_step_id"], "plan")

    resumed = self.call_mcp_tool("feature_implement_workflow_resume", {"workflow_id": workflow_id})
    self.assertEqual(resumed["status"], "ok")
    self.assertEqual(resumed["resume_step_id"], "plan")
    self.assertTrue(resumed["can_resume"])

    continued = self.call_mcp_tool("feature_implement_workflow_continue", {"workflow_id": workflow_id})
    self.assertEqual(continued["status"], "ok")
    self.assertEqual(continued["continue_step_id"], "plan")

    finished = self.harness.complete_from_continuation_payload(continued)
    final_workflow = finished["final_workflow"]
    assert isinstance(final_workflow, dict)
    self.assertEqual(final_workflow["workflow_status"], "completed")

  def test_mcp_workflow_continue_blocked_surfaces_missing_artifacts(self) -> None:
    opened = self.call_mcp_tool("feature_implement_workflow_open", {})
    workflow_id = opened["workflow_id"]
    self.call_mcp_tool(
      "feature_implement_workflow_update",
      {
        "workflow_id": workflow_id,
        "workflow_status": "failed",
        "current_step_id": "validate",
        "step_updates": [
          {"step_id": "validate", "status": "failed", "attempt_count": 1},
        ],
      },
    )

    continued = self.call_mcp_tool("feature_implement_workflow_continue", {"workflow_id": workflow_id})
    self.assertEqual(continued["status"], "error")
    self.assertEqual(continued["continue_status"], "blocked")
    self.assertIn("audit_report", continued["missing_artifacts"])

  def test_mcp_and_cli_latest_are_consistent_after_continue_reopens_state(self) -> None:
    first_id = self.seed_plan_interrupted_workflow()
    second_id = self.seed_review_interrupted_workflow()

    continue_result = self.run_cli("workflow", "continue", second_id, "--format", "json")
    self.assertEqual(continue_result.returncode, 0, continue_result.stderr)
    continue_payload = json.loads(continue_result.stdout)
    self.assertEqual(continue_payload["workflow_id"], second_id)

    cli_latest = self.run_cli("workflow", "show", "--latest", "--format", "json")
    self.assertEqual(cli_latest.returncode, 0, cli_latest.stderr)
    cli_payload = json.loads(cli_latest.stdout)
    self.assertEqual(cli_payload["workflow_id"], second_id)
    self.assertNotEqual(first_id, second_id)

    mcp_latest = self.call_mcp_tool("feature_implement_workflow_latest", {})
    self.assertEqual(mcp_latest["status"], "ok")
    self.assertEqual(mcp_latest["workflow_id"], second_id)


if __name__ == "__main__":
  unittest.main()
