from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
from unittest import mock

from skill_bill import launcher


class LauncherTest(unittest.TestCase):
  def make_packaged_bin(self, root: Path, relative_path: str) -> Path:
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
    path.chmod(0o755)
    return path

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

  def test_kotlin_cli_command_uses_packaged_distribution_by_default(self):
    with tempfile.TemporaryDirectory() as temp_dir:
      root = Path(temp_dir)
      bin_path = self.make_packaged_bin(
        root,
        "runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli",
      )
      with mock.patch.object(launcher, "repo_root", return_value=root):
        command = launcher.kotlin_cli_command(["doctor", "--format", "json"], {})

    self.assertEqual([str(bin_path), "doctor", "--format", "json"], command)
    self.assertNotIn("gradlew", " ".join(command))
    self.assertNotIn(":runtime-cli:run", command)

  def test_kotlin_cli_command_reports_missing_packaged_distribution(self):
    with tempfile.TemporaryDirectory() as temp_dir:
      root = Path(temp_dir)
      with mock.patch.object(launcher, "repo_root", return_value=root):
        with self.assertRaises(launcher.MissingKotlinDistributionError) as raised:
          launcher.kotlin_cli_command(["doctor"], {})

    message = str(raised.exception)
    self.assertIn("Packaged Kotlin runtime distribution not found", message)
    self.assertIn("runtime-cli/build/install/runtime-cli/bin/runtime-cli", message)
    self.assertIn(":runtime-cli:installDist", message)

  def test_selected_mcp_runtime_defaults_to_kotlin(self):
    self.assertEqual("kotlin", launcher.selected_mcp_runtime({}))

  def test_kotlin_mcp_command_uses_override_when_provided(self):
    command = launcher.kotlin_mcp_command({"SKILL_BILL_KOTLIN_MCP": "/tmp/skill-bill-mcp-kotlin"})
    self.assertEqual(["/tmp/skill-bill-mcp-kotlin"], command)

  def test_kotlin_mcp_command_uses_packaged_distribution_by_default(self):
    with tempfile.TemporaryDirectory() as temp_dir:
      root = Path(temp_dir)
      bin_path = self.make_packaged_bin(
        root,
        "runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp",
      )
      with mock.patch.object(launcher, "repo_root", return_value=root):
        command = launcher.kotlin_mcp_command({})

    self.assertEqual([str(bin_path)], command)
    self.assertNotIn("gradlew", " ".join(command))
    self.assertNotIn(":runtime-mcp:run", command)

  def test_kotlin_mcp_command_reports_missing_packaged_distribution(self):
    with tempfile.TemporaryDirectory() as temp_dir:
      root = Path(temp_dir)
      with mock.patch.object(launcher, "repo_root", return_value=root):
        with self.assertRaises(launcher.MissingKotlinDistributionError) as raised:
          launcher.kotlin_mcp_command({})

    message = str(raised.exception)
    self.assertIn("Packaged Kotlin runtime distribution not found", message)
    self.assertIn("runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp", message)
    self.assertIn(":runtime-mcp:installDist", message)

  def test_main_routes_python_runtime_to_python_cli(self):
    with mock.patch.object(launcher, "python_cli_main", return_value=7) as python_main:
      with mock.patch.object(launcher, "selected_runtime", return_value="python"):
        self.assertEqual(7, launcher.main(["version"]))
    python_main.assert_called_once_with(["version"])

  def test_mcp_kotlin_runtime_routes_to_kotlin_command(self):
    completed = mock.Mock(returncode=7)
    with mock.patch.dict("os.environ", {"SKILL_BILL_MCP_RUNTIME": "kotlin"}):
      with mock.patch.object(launcher.subprocess, "run", return_value=completed) as run:
        with mock.patch.object(launcher, "kotlin_mcp_command", return_value=["/tmp/runtime-mcp"]):
          with self.assertRaises(SystemExit) as raised:
            launcher.mcp_main()
    self.assertEqual(7, raised.exception.code)
    run.assert_called_once()

  def test_mcp_kotlin_runtime_routes_missing_distribution_to_exit_code_2(self):
    with mock.patch.dict("os.environ", {"SKILL_BILL_MCP_RUNTIME": "kotlin"}):
      with mock.patch.object(
        launcher,
        "kotlin_mcp_command",
        side_effect=launcher.MissingKotlinDistributionError("missing packaged runtime"),
      ):
        with mock.patch("sys.stderr"):
          with self.assertRaises(SystemExit) as raised:
            launcher.mcp_main()
    self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
  unittest.main()
