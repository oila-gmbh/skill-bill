from __future__ import annotations

import json
import sqlite3
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
INSTALL_SCRIPT = ROOT / "install.sh"
SKILLS_DIR = ROOT / "skills"
PLATFORM_PACKS_DIR = ROOT / "platform-packs"


def skill_names(package_name: str) -> set[str]:
  """Return every skill name belonging to a package, across both trees.

  SKILL-14 split platform code-review content out of ``skills/`` into
  ``platform-packs/``. The installer walks both trees; the test helpers
  must mirror that union so the installer-behavior assertions stay
  accurate.
  """
  names: set[str] = set()
  if package_name == "base":
    names.update(
      skill_file.parent.name for skill_file in SKILLS_DIR.glob("bill-*/SKILL.md")
    )
  else:
    skills_package = SKILLS_DIR / package_name
    if skills_package.is_dir():
      names.update(
        skill_file.parent.name for skill_file in skills_package.glob("*/SKILL.md")
      )
  packs_package = PLATFORM_PACKS_DIR / package_name
  if packs_package.is_dir():
    names.update(
      skill_file.parent.name for skill_file in packs_package.rglob("SKILL.md")
    )
  return names


def platform_package_names() -> list[str]:
  return sorted(
    entry.name
    for entry in PLATFORM_PACKS_DIR.iterdir()
    if entry.is_dir() and not entry.name.startswith(".")
  )


BASE_SKILLS = skill_names("base")
KOTLIN_SKILLS = skill_names("kotlin")
KMP_SKILLS = skill_names("kmp")
PLATFORM_PACKAGES = platform_package_names()
ALL_PLATFORM_SKILLS = set().union(*(skill_names(package) for package in PLATFORM_PACKAGES))


class InstallScriptTest(unittest.TestCase):
  maxDiff = None

  def test_accepts_multi_agent_selection_without_primary_prompt(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      self.prepare_agent_homes(temp_home)
      result = self.run_installer(temp_home, "all\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Available agents:", result.stdout)
      self.assertNotIn("Choose the primary agent:", result.stdout)
      self.assertNotIn("Primary:", result.stdout)
      self.assertNotIn("via copilot", result.stdout)
      self.assertNotIn("Choose the user-facing skill prefix.", result.stdout)
      self.assertNotIn("Command prefix:", result.stdout)

      for relative_path in (
        ".copilot/skills/bill-code-review",
        ".claude/commands/bill-code-review",
        ".glm/commands/bill-code-review",
        ".codex/skills/bill-code-review",
        ".config/opencode/skills/bill-code-review",
      ):
        path = Path(temp_home) / relative_path
        self.assertTrue(path.is_symlink(), relative_path)
        self.assertEqual(path.resolve(), ROOT / "skills" / "bill-code-review")

  def test_installs_base_and_selected_platform_only(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Available optional platforms:", result.stdout)
      self.assertIn("Base skills are always installed.", result.stdout)
      self.assertIn("Choose one or more optional platform numbers (comma-separated).", result.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | KOTLIN_SKILLS)
      self.assertFalse((Path(temp_home) / ".copilot" / "skills" / ".bill-shared").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-code-review" / "stack-routing.md").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-code-review" / "shell-ceremony.md").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-kotlin-code-review" / "review-orchestrator.md").exists())
      self.assertTrue((Path(temp_home) / ".copilot" / "skills" / "bill-kotlin-code-review" / "shell-ceremony.md").exists())
      self.assertIn("Installed agent: copilot", result.stdout)
      self.assertEqual(self.telemetry_config(Path(temp_home) / ".skill-bill" / "config.json")["telemetry"]["level"], "anonymous")

  def test_installs_base_and_selected_kmp_platform_only(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKMP\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | KMP_SKILLS)

  def test_installs_opencode_skills_and_registers_mcp(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "opencode\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home, relative_dir=".config/opencode/skills")
      self.assertEqual(installed, BASE_SKILLS | KOTLIN_SKILLS)
      self.assertIn("Installed agent: opencode", result.stdout)

      config = json.loads((Path(temp_home) / ".config" / "opencode" / "opencode.json").read_text(encoding="utf-8"))
      self.assertEqual(config["mcp"]["skill-bill"]["type"], "local")
      self.assertEqual(
        config["mcp"]["skill-bill"]["command"][1:],
        ["-c", "from skill_bill.launcher import mcp_main; mcp_main()"],
      )
      self.assertTrue(config["mcp"]["skill-bill"]["enabled"])

  def test_installer_merges_opencode_jsonc_config_when_registering_mcp(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      config_path = Path(temp_home) / ".config" / "opencode" / "opencode.json"
      config_path.parent.mkdir(parents=True, exist_ok=True)
      config_path.write_text('{\n  // keep this user setting\n  "theme": "opencode",\n}\n', encoding="utf-8")

      result = self.run_installer(temp_home, "opencode\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      config = json.loads(config_path.read_text(encoding="utf-8"))
      self.assertEqual(config["theme"], "opencode")
      self.assertIn("skill-bill", config["mcp"])

  def test_accepts_numeric_multi_platform_selection(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      all_option = str(len(PLATFORM_PACKAGES) + 1)
      result = self.run_installer(temp_home, f"copilot\n{all_option}\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | ALL_PLATFORM_SKILLS)

  def test_accepts_human_friendly_multi_platform_selection(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      selection = ", ".join(PLATFORM_PACKAGES)
      result = self.run_installer(temp_home, f"copilot\n{selection}\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | ALL_PLATFORM_SKILLS)

  def test_shows_each_platform_option_and_supports_all(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nall\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      for package in PLATFORM_PACKAGES:
        self.assertIn(f"({package})", result.stdout)
      self.assertIn("all (install every platform package)", result.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | ALL_PLATFORM_SKILLS)

  def test_ignores_empty_platform_tokens(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      selection = ", , ".join(PLATFORM_PACKAGES)
      result = self.run_installer(temp_home, f"copilot\n{selection}\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | ALL_PLATFORM_SKILLS)

  def test_rerun_reinstalls_only_selected_platforms(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      first = self.run_installer(temp_home, "copilot\nKMP\n")
      self.assertEqual(first.returncode, 0, first.stdout + first.stderr)

      second = self.run_installer(
        temp_home,
        "copilot\nKotlin\n",
      )
      self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
      self.assertIn("Skill Bill Uninstaller", second.stdout)

      installed = self.installed_skills(temp_home)
      self.assertEqual(installed, BASE_SKILLS | KOTLIN_SKILLS)

  def test_rerun_replaces_existing_skill_directory_and_restores_sidecars(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      first = self.run_installer(temp_home, "copilot\nKotlin\n")
      self.assertEqual(first.returncode, 0, first.stdout + first.stderr)

      installed_skill = Path(temp_home) / ".copilot" / "skills" / "bill-code-review"
      self.assertTrue(installed_skill.is_symlink())
      installed_skill.unlink()
      shutil.copytree(ROOT / "skills" / "bill-code-review", installed_skill, symlinks=False)
      (installed_skill / "stack-routing.md").write_text("stale sidecar", encoding="utf-8")

      second = self.run_installer(temp_home, "copilot\nKotlin\n")
      self.assertEqual(second.returncode, 0, second.stdout + second.stderr)

      self.assertTrue(installed_skill.is_symlink())
      self.assertEqual(installed_skill.resolve(), ROOT / "skills" / "bill-code-review")
      self.assertTrue((installed_skill / "stack-routing.md").exists())

  def test_install_removes_legacy_backend_kotlin_aliases(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      legacy_target = Path(temp_home) / "legacy-backend-skill"
      legacy_target.write_text("legacy", encoding="utf-8")
      legacy_symlink = Path(temp_home) / ".copilot" / "skills" / "bill-backend-kotlin-code-review-reliability"
      legacy_symlink.parent.mkdir(parents=True, exist_ok=True)
      legacy_symlink.symlink_to(legacy_target)

      result = self.run_installer(temp_home, "copilot\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertFalse(legacy_symlink.exists())
      self.assertIn(
        "removed bill-backend-kotlin-code-review-reliability",
        result.stdout,
      )

  def test_installer_writes_telemetry_config_with_default_anonymous(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKotlin\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertIn("Choose a telemetry level", result.stdout)

      config = self.telemetry_config(Path(temp_home) / ".skill-bill" / "config.json")
      self.assertEqual(
        config["telemetry"],
        {
          "batch_size": 50,
          "level": "anonymous",
          "proxy_url": "",
        },
      )
      self.assertTrue(config["install_id"])

  def test_installer_supports_telemetry_opt_out_without_creating_state(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKotlin\n3\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      state_dir = Path(temp_home) / ".skill-bill"
      self.assertFalse((state_dir / "config.json").exists())
      self.assertFalse((state_dir / "review-metrics.db").exists())

  def test_installer_respects_custom_telemetry_config_path_when_enabled(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      custom_state_dir = Path(temp_home) / "custom-state"
      custom_config_path = custom_state_dir / "config.json"
      custom_db_path = custom_state_dir / "review-metrics.db"

      result = self.run_installer(
        temp_home,
        "copilot\nKotlin\n",
        extra_env={
          "SKILL_BILL_CONFIG_PATH": str(custom_config_path),
          "SKILL_BILL_REVIEW_DB": str(custom_db_path),
        },
      )
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      self.assertFalse((Path(temp_home) / ".skill-bill" / "config.json").exists())
      self.assertTrue(custom_config_path.exists())
      self.assertEqual(self.telemetry_config(custom_config_path)["telemetry"]["level"], "anonymous")

  def test_installer_respects_custom_telemetry_paths_when_disabled(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      custom_state_dir = Path(temp_home) / "custom-state"
      custom_config_path = custom_state_dir / "config.json"
      custom_db_path = custom_state_dir / "review-metrics.db"
      custom_state_dir.mkdir(parents=True, exist_ok=True)
      custom_config_path.write_text(
        json.dumps(
          {
            "install_id": "install-test-123",
            "telemetry": {
              "enabled": True,
              "proxy_url": "",
              "batch_size": 50,
            },
          },
          indent=2,
          sort_keys=True,
        ) + "\n",
        encoding="utf-8",
      )
      connection = sqlite3.connect(custom_db_path)
      try:
        connection.execute(
          """
          CREATE TABLE telemetry_outbox (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_name TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            synced_at TEXT,
            last_error TEXT NOT NULL DEFAULT ''
          )
          """
        )
        connection.execute(
          """
          INSERT INTO telemetry_outbox (event_name, payload_json)
          VALUES (?, ?)
          """,
          ("skillbill_review_finished", "{}"),
        )
        connection.commit()
      finally:
        connection.close()

      result = self.run_installer(
        temp_home,
        "copilot\nKotlin\n3\n",
        extra_env={
          "SKILL_BILL_CONFIG_PATH": str(custom_config_path),
          "SKILL_BILL_REVIEW_DB": str(custom_db_path),
        },
      )
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      self.assertFalse((Path(temp_home) / ".skill-bill" / "config.json").exists())
      self.assertFalse(custom_config_path.exists())
      connection = sqlite3.connect(custom_db_path)
      try:
        remaining_events = connection.execute("SELECT COUNT(*) FROM telemetry_outbox").fetchone()[0]
      finally:
        connection.close()
      self.assertEqual(remaining_events, 0)

  def test_installer_supports_full_telemetry_level(self) -> None:
    with tempfile.TemporaryDirectory() as temp_home:
      result = self.run_installer(temp_home, "copilot\nKotlin\n2\n")
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

      config = self.telemetry_config(Path(temp_home) / ".skill-bill" / "config.json")
      self.assertEqual(config["telemetry"]["level"], "full")
      self.assertNotIn("enabled", config["telemetry"])

  def run_installer(
    self,
    temp_home: str,
    user_input: str,
    *,
    extra_env: dict[str, str] | None = None,
  ) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["HOME"] = temp_home
    if extra_env is not None:
      env.update(extra_env)
    return subprocess.run(
      ["bash", str(INSTALL_SCRIPT)],
      cwd=ROOT,
      input=user_input,
      capture_output=True,
      text=True,
      check=False,
      env=env,
    )

  def installed_skills(self, temp_home: str, *, relative_dir: str = ".copilot/skills") -> set[str]:
    install_dir = Path(temp_home) / relative_dir
    if not install_dir.exists():
      return set()
    return {path.name for path in install_dir.iterdir() if not path.name.startswith(".")}

  def prepare_agent_homes(self, temp_home: str) -> None:
    for relative_dir in (
      ".copilot",
      ".claude",
      ".glm",
      ".codex",
      ".config/opencode",
    ):
      (Path(temp_home) / relative_dir).mkdir(parents=True, exist_ok=True)

  def telemetry_config(self, config_path: Path) -> dict[str, object]:
    return json.loads(config_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
  unittest.main()
