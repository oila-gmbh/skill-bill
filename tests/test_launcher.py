from __future__ import annotations

import unittest
from unittest import mock

from skill_bill import launcher


class LauncherTest(unittest.TestCase):
  def test_selected_runtime_defaults_to_kotlin(self):
    self.assertEqual("kotlin", launcher.selected_runtime({}))

  def test_selected_runtime_accepts_python_fallback(self):
    self.assertEqual("python", launcher.selected_runtime({"SKILL_BILL_RUNTIME": "python"}))

  def test_kotlin_cli_command_uses_override_when_provided(self):
    command = launcher.kotlin_cli_command(
      ["version", "--format", "json"],
      {"SKILL_BILL_KOTLIN_CLI": "/tmp/skill-bill-kotlin --flag"},
    )
    self.assertEqual(
      ["/tmp/skill-bill-kotlin", "--flag", "version", "--format", "json"],
      command,
    )

  def test_main_routes_python_runtime_to_python_cli(self):
    with mock.patch.object(launcher, "python_cli_main", return_value=7) as python_main:
      with mock.patch.object(launcher, "selected_runtime", return_value="python"):
        self.assertEqual(7, launcher.main(["version"]))
    python_main.assert_called_once_with(["version"])

  def test_mcp_kotlin_runtime_reports_not_packaged(self):
    with mock.patch.dict("os.environ", {"SKILL_BILL_MCP_RUNTIME": "kotlin"}):
      with self.assertRaises(SystemExit) as raised:
        launcher.mcp_main()
    self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
  unittest.main()
