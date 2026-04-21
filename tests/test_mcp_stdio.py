from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

SAMPLE_REVIEW = """\
Routed to: bill-kotlin-code-review
Review session ID: rvs-20260405-stdio
Review run ID: rvw-20260405-stdio
Detected review scope: unstaged changes
Detected stack: kotlin
Signals: README.md, install.sh
Execution mode: inline
Reason: kotlin signals dominate

### 2. Risk Register
- [F-001] Major | High | README.md:12 | README wording is stale after the routing change.
- [F-002] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
"""

EXPECTED_TOOLS = {
  "doctor",
  "feature_implement_finished",
  "feature_implement_started",
  "feature_implement_workflow_get",
  "feature_implement_workflow_latest",
  "feature_implement_workflow_list",
  "feature_implement_workflow_continue",
  "feature_implement_workflow_open",
  "feature_implement_workflow_resume",
  "feature_implement_workflow_update",
  "feature_verify_finished",
  "feature_verify_started",
  "import_review",
  "new_skill_scaffold",
  "pr_description_generated",
  "quality_check_finished",
  "quality_check_started",
  "resolve_learnings",
  "review_stats",
  "triage_findings",
}


class McpStdioTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.env = {
      key: value
      for key, value in os.environ.items()
      if not key.startswith("SKILL_BILL_")
    }
    self.env.update({
      "SKILL_BILL_REVIEW_DB": os.path.join(self.temp_dir, "metrics.db"),
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      # Belt-and-braces: even when telemetry is off in this fixture, pin the
      # relay URL so an accidental enable can never ship to prod (#43).
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
    })

  def tearDown(self) -> None:
    import shutil
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _server_params(self) -> StdioServerParameters:
    return StdioServerParameters(
      command=sys.executable,
      args=["-m", "skill_bill.mcp_server"],
      env=self.env,
    )

  def _run(self, coro):
    return asyncio.run(coro)

  def test_list_tools_returns_expected_set(self) -> None:
    async def run():
      async with stdio_client(self._server_params()) as (read, write):
        async with ClientSession(read, write) as session:
          await session.initialize()
          result = await session.list_tools()
          return {t.name for t in result.tools}
    tool_names = self._run(run())
    self.assertEqual(tool_names, EXPECTED_TOOLS)

  def test_doctor_returns_version(self) -> None:
    async def run():
      async with stdio_client(self._server_params()) as (read, write):
        async with ClientSession(read, write) as session:
          await session.initialize()
          result = await session.call_tool("doctor", {})
          return json.loads(result.content[0].text)
    content = self._run(run())
    self.assertIn("version", content)
    self.assertIn("db_path", content)
    self.assertFalse(content["telemetry_enabled"])

  def test_import_review_end_to_end(self) -> None:
    async def run():
      async with stdio_client(self._server_params()) as (read, write):
        async with ClientSession(read, write) as session:
          await session.initialize()
          result = await session.call_tool("import_review", {"review_text": SAMPLE_REVIEW})
          return json.loads(result.content[0].text)
    content = self._run(run())
    self.assertEqual(content["review_run_id"], "rvw-20260405-stdio")
    self.assertEqual(content["finding_count"], 2)


if __name__ == "__main__":
  unittest.main()
