from __future__ import annotations

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
INSTALL_SCRIPT = ROOT / "install.sh"
UNINSTALL_SCRIPT = ROOT / "uninstall.sh"


class UninstallScriptTest(unittest.TestCase):
  maxDiff = None

  def test_uninstall_removes_skill_symlinks_from_supported_agents(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      install = self.run_script(INSTALL_SCRIPT, temp_home, "copilot, claude\ncopilot\nPHP\n")
      self.assertEqual(install.returncode, 0, install.stdout + install.stderr)
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-code-review").is_symlink())
      self.assertTrue((Path(temp_home) / ".claude" / "commands" / "bill-code-review").is_symlink())

      uninstall = self.run_script(UNINSTALL_SCRIPT, temp_home)
      self.assertEqual(uninstall.returncode, 0, uninstall.stdout + uninstall.stderr)
      self.assertIn("Removed symlinks:", uninstall.stdout)
      self.assertFalse((Path(temp_home) / ".copilot" / "skills" / "bill-code-review").exists())
      self.assertFalse((Path(temp_home) / ".claude" / "commands" / "bill-code-review").exists())

  def test_uninstall_removes_legacy_skill_symlinks_and_is_idempotent(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      legacy_target = Path(temp_home) / "legacy-target"
      legacy_target.write_text("legacy", encoding="utf-8")
      legacy_symlink = Path(temp_home) / ".copilot" / "skills" / "bill-gcheck"
      legacy_symlink.symlink_to(legacy_target)

      first = self.run_script(UNINSTALL_SCRIPT, temp_home)
      self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
      self.assertFalse(legacy_symlink.exists())

      second = self.run_script(UNINSTALL_SCRIPT, temp_home)
      self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
      self.assertIn("Removed symlinks: 0", second.stdout)

  def prepare_agent_homes(self, temp_home: str) -> None:
    for relative_dir in (
      ".copilot/skills",
      ".claude/commands",
      ".glm/commands",
      ".codex/skills",
      ".agents/skills",
    ):
      (Path(temp_home) / relative_dir).mkdir(parents=True, exist_ok=True)

  def run_script(
    self,
    script_path: Path,
    temp_home: str,
    user_input: str = "",
  ) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["HOME"] = temp_home
    return subprocess.run(
      ["bash", str(script_path)],
      cwd=ROOT,
      input=user_input,
      capture_output=True,
      text=True,
      check=False,
      env=env,
    )


if __name__ == "__main__":
  unittest.main()
