from __future__ import annotations

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
INSTALL_SCRIPT = ROOT / "install.sh"
SKILLS_DIR = ROOT / "skills"


def skill_names(package_name: str) -> set[str]:
  return {
    skill_file.parent.name
    for skill_file in (SKILLS_DIR / package_name).glob("*/SKILL.md")
  }


BASE_SKILLS = skill_names("base")
BACKEND_KOTLIN_SKILLS = skill_names("backend-kotlin")
KOTLIN_SKILLS = skill_names("kotlin")
KMP_SKILLS = skill_names("kmp")
PHP_SKILLS = skill_names("php")
GO_SKILLS = skill_names("go")


class InstallScriptTest(unittest.TestCase):
  maxDiff = None

  def test_accepts_multi_agent_selection_without_primary_prompt(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      result = self.run_installer(temp_home, "all\nPHP\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Available agents:", result.stdout)
      self.assertNotIn("Choose the primary agent:", result.stdout)
      self.assertNotIn("Primary:", result.stdout)
      self.assertNotIn("via copilot", result.stdout)

      for relative_path in (
        ".copilot/skills/bill-code-review",
        ".claude/commands/bill-code-review",
        ".glm/commands/bill-code-review",
        ".codex/skills/bill-code-review",
      ):
        path = Path(temp_home) / relative_path
        self.assertTrue(path.is_symlink(), relative_path)
        self.assertEqual(path.resolve(), ROOT / "skills" / "base" / "bill-code-review")

  def test_installs_base_and_selected_platform_only(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nPHP\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Available platforms:", result.stdout)
      self.assertIn("Base skills are always installed.", result.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | PHP_SKILLS)
      self.assertFalse((Path(temp_home) / ".copilot" / "skills" / ".bill-shared").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-code-review" / "stack-routing.md").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-php-code-review" / "review-orchestrator.md").exists())
      self.assertIn("Installed agent: copilot", result.stdout)

  def test_installs_base_and_selected_go_platform_only(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nGo\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | GO_SKILLS)

  def test_accepts_human_friendly_multi_platform_selection(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKotlin backend, Kotlin, KMP\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | BACKEND_KOTLIN_SKILLS | KOTLIN_SKILLS | KMP_SKILLS)
      self.assertTrue(PHP_SKILLS.isdisjoint(installed))

  def test_shows_each_platform_option_and_supports_all(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nall\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Kotlin backend (backend-kotlin)", result.stdout)
      self.assertIn("Kotlin (kotlin)", result.stdout)
      self.assertIn("KMP (kmp)", result.stdout)
      self.assertIn("PHP (php)", result.stdout)
      self.assertIn("Go (go)", result.stdout)
      self.assertIn("all (install every platform package)", result.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(
        installed,
        BASE_SKILLS | BACKEND_KOTLIN_SKILLS | KOTLIN_SKILLS | KMP_SKILLS | PHP_SKILLS | GO_SKILLS,
      )

  def test_ignores_empty_platform_tokens(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nPHP, , Kotlin backend\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | PHP_SKILLS | BACKEND_KOTLIN_SKILLS)

  def test_rerun_reinstalls_only_selected_platforms(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      first = self.run_installer(temp_home, "copilot\nPHP\n")
      self.assertEqual(first.returncode, 0, first.stdout + first.stderr)

      second = self.run_installer(
        temp_home,
        "copilot\nKotlin backend\n",
      )
      self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
      self.assertIn("Skill Bill Uninstaller", second.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | BACKEND_KOTLIN_SKILLS)

  def test_rerun_replaces_existing_skill_directory_and_restores_sidecars(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      first = self.run_installer(temp_home, "copilot\nPHP\n")
      self.assertEqual(first.returncode, 0, first.stdout + first.stderr)

      installed_skill = Path(temp_home) / ".copilot" / "skills" / "bill-code-review"
      self.assertTrue(installed_skill.is_symlink())
      installed_skill.unlink()
      shutil.copytree(ROOT / "skills" / "base" / "bill-code-review", installed_skill, symlinks=False)
      (installed_skill / "stack-routing.md").write_text("stale sidecar", encoding="utf-8")

      second = self.run_installer(temp_home, "copilot\nPHP\n")
      self.assertEqual(second.returncode, 0, second.stdout + second.stderr)

      self.assertTrue(installed_skill.is_symlink())
      self.assertEqual(installed_skill.resolve(), ROOT / "skills" / "base" / "bill-code-review")
      self.assertTrue((installed_skill / "stack-routing.md").exists())

  def run_installer(
    self,
    temp_home: str,
    user_input: str,
  ) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["HOME"] = temp_home
    return subprocess.run(
      ["bash", str(INSTALL_SCRIPT)],
      cwd=ROOT,
      input=user_input,
      capture_output=True,
      text=True,
      check=False,
      env=env,
    )

  def installed_skills(self, temp_home: str) -> set[str]:
    install_dir = Path(temp_home) / ".copilot" / "skills"
    if not install_dir.exists():
      return set()
    return {path.name for path in install_dir.iterdir() if path.name.startswith("bill-")}

  def prepare_agent_homes(self, temp_home: str) -> None:
    for relative_dir in (
      ".copilot",
      ".claude",
      ".glm",
      ".codex",
    ):
      (Path(temp_home) / relative_dir).mkdir(parents=True, exist_ok=True)


if __name__ == "__main__":
  unittest.main()
