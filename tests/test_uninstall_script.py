from __future__ import annotations

import json
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
      self.assertIn("Removed installs:", uninstall.stdout)
      self.assertFalse((Path(temp_home) / ".copilot" / "skills" / "bill-code-review").exists())
      self.assertFalse((Path(temp_home) / ".claude" / "commands" / "bill-code-review").exists())
      self.assertFalse((Path(temp_home) / ".copilot" / "skills" / ".bill-shared").exists())

  def test_uninstall_removes_legacy_managed_install_directories(self) -> None:
    """Earlier installer versions supported a custom prefix that generated
    real directories marked with .skill-bill-install. The uninstaller must
    still clean those up for users upgrading from prior versions."""
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      managed_dir = Path(temp_home) / ".copilot" / "skills" / "acme-code-review"
      managed_dir.mkdir(parents=True)
      (managed_dir / "SKILL.md").write_text("name: acme-code-review\n", encoding="utf-8")
      (managed_dir / ".skill-bill-install").write_text(
        "managed_by=skill-bill\ncanonical_name=bill-code-review\ninstalled_name=acme-code-review\nprefix=acme\n",
        encoding="utf-8",
      )

      uninstall = self.run_script(UNINSTALL_SCRIPT, temp_home)
      self.assertEqual(uninstall.returncode, 0, uninstall.stdout + uninstall.stderr)
      self.assertFalse(managed_dir.exists())
      self.assertIn("Removed installs:", uninstall.stdout)

  def test_uninstall_removes_opencode_skill_and_mcp_registration(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      install = self.run_script(INSTALL_SCRIPT, temp_home, "opencode\nPHP\n")
      self.assertEqual(install.returncode, 0, install.stdout + install.stderr)
      self.assertTrue((Path(temp_home) / ".config" / "opencode" / "skills" / "bill-code-review").is_symlink())

      config_path = Path(temp_home) / ".config" / "opencode" / "opencode.json"
      config = json.loads(config_path.read_text(encoding="utf-8"))
      self.assertIn("skill-bill", config["mcp"])

      uninstall = self.run_script(UNINSTALL_SCRIPT, temp_home)
      self.assertEqual(uninstall.returncode, 0, uninstall.stdout + uninstall.stderr)
      self.assertFalse((Path(temp_home) / ".config" / "opencode" / "skills" / "bill-code-review").exists())

      config = json.loads(config_path.read_text(encoding="utf-8"))
      self.assertNotIn("mcp", config)

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
      self.assertIn("Removed installs: 0", second.stdout)

  def prepare_agent_homes(self, temp_home: str) -> None:
    for relative_dir in (
      ".copilot/skills",
      ".claude/commands",
      ".glm/commands",
      ".codex/skills",
      ".agents/skills",
      ".config/opencode/skills",
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
