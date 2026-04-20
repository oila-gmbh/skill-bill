"""SKILL-21 AC 15(d): CLI surface coverage.

Exercises the new ``skill-bill edit``, ``skill-bill upgrade``, and the
doctor extensions (content_md_missing, template_version_drift). The
orchestrator (edit/upgrade/doctor) operates on an in-memory repo seeded
from the scaffold test helpers so tests stay hermetic.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


from skill_bill import cli  # noqa: E402
from skill_bill.constants import TEMPLATE_VERSION  # noqa: E402
from skill_bill.shell_content_contract import CANONICAL_EXECUTION_BODY  # noqa: E402


def _seed_governed_skill(repo_root: Path) -> Path:
  """Seed a single governed skill with v1.1 shape and return its directory."""
  pack_root = repo_root / "platform-packs" / "fixture"
  skill_dir = pack_root / "code-review" / "bill-fixture-code-review"
  skill_dir.mkdir(parents=True)
  (skill_dir / "SKILL.md").write_text(
    "---\n"
    "name: bill-fixture-code-review\n"
    "description: Fixture.\n"
    f"shell_contract_version: 1.1\n"
    f"template_version: {TEMPLATE_VERSION}\n"
    "---\n"
    "\n"
    "## Description\nFixture.\n\n"
    "## Specialist Scope\nFixture.\n\n"
    "## Inputs\nFixture.\n\n"
    "## Outputs Contract\nFixture.\n\n"
    + CANONICAL_EXECUTION_BODY
    + "\n"
    "## Execution Mode Reporting\nFixture.\n\n"
    "## Telemetry Ceremony Hooks\nFixture.\n",
    encoding="utf-8",
  )
  (skill_dir / "content.md").write_text(
    "# bill-fixture-code-review\n\nFixture body.\n",
    encoding="utf-8",
  )
  (pack_root / "platform.yaml").write_text(
    "platform: fixture\n"
    "contract_version: \"1.1\"\n"
    "display_name: Fixture\n"
    "routing_signals:\n"
    "  strong:\n"
    "    - .fixture\n"
    "  tie_breakers: []\n"
    "  addon_signals: []\n"
    "declared_code_review_areas: []\n"
    "declared_files:\n"
    "  baseline: code-review/bill-fixture-code-review/SKILL.md\n"
    "  areas: {}\n",
    encoding="utf-8",
  )
  return skill_dir


class EditCommandTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = Path(self._tmpdir.name) / "repo"
    self.repo.mkdir()
    (self.repo / "skills").mkdir()
    self.skill_dir = _seed_governed_skill(self.repo)
    self._patcher = mock.patch.object(cli, "_resolve_repo_root_for_cli", return_value=self.repo)
    self._patcher.start()
    self.addCleanup(self._patcher.stop)

  def test_edit_uses_visual(self) -> None:
    captured: dict[str, object] = {}

    def fake_run(command, **_kwargs):
      captured["command"] = command
      class Result:
        returncode = 0
      return Result()

    args = argparse.Namespace(skill_name="bill-fixture-code-review")
    env = {"VISUAL": "vim-mock", "EDITOR": "emacs-mock"}
    with mock.patch.dict(os.environ, env, clear=False):
      with mock.patch("subprocess.run", side_effect=fake_run):
        code = cli.edit_skill_command(args)
    self.assertEqual(code, 0)
    self.assertEqual(captured["command"][0], "vim-mock")
    self.assertTrue(str(captured["command"][-1]).endswith("content.md"))

  def test_edit_falls_back_to_editor_when_visual_is_unset(self) -> None:
    captured: dict[str, object] = {}

    def fake_run(command, **_kwargs):
      captured["command"] = command
      class Result:
        returncode = 0
      return Result()

    env = {"EDITOR": "emacs-mock"}
    with mock.patch.dict(os.environ, env, clear=True):
      with mock.patch("subprocess.run", side_effect=fake_run):
        code = cli.edit_skill_command(argparse.Namespace(skill_name="bill-fixture-code-review"))
    self.assertEqual(code, 0)
    self.assertEqual(captured["command"][0], "emacs-mock")

  def test_edit_prints_path_when_no_editor_configured(self) -> None:
    env: dict[str, str] = {}
    buf = io.StringIO()
    with mock.patch.dict(os.environ, env, clear=True):
      with redirect_stdout(buf):
        code = cli.edit_skill_command(
          argparse.Namespace(skill_name="bill-fixture-code-review")
        )
    self.assertEqual(code, 0)
    self.assertIn("content.md", buf.getvalue())
    # The printed path must resolve to content.md, never SKILL.md.
    self.assertNotIn("SKILL.md", buf.getvalue())

  def test_edit_rejects_unknown_skill(self) -> None:
    buf = io.StringIO()
    with redirect_stderr(buf):
      code = cli.edit_skill_command(argparse.Namespace(skill_name="bill-does-not-exist"))
    self.assertEqual(code, 2)
    self.assertIn("not found", buf.getvalue())


class UpgradeCommandTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = Path(self._tmpdir.name) / "repo"
    self.repo.mkdir()
    (self.repo / "skills").mkdir()
    self.skill_dir = _seed_governed_skill(self.repo)
    self._patcher = mock.patch.object(cli, "_resolve_repo_root_for_cli", return_value=self.repo)
    self._patcher.start()
    self.addCleanup(self._patcher.stop)

  def _mark_template_drift(self) -> None:
    skill_file = self.skill_dir / "SKILL.md"
    text = skill_file.read_text(encoding="utf-8")
    text = text.replace(
      f"template_version: {TEMPLATE_VERSION}\n",
      "template_version: 2020.01.01\n",
    )
    skill_file.write_text(text, encoding="utf-8")

  def test_upgrade_dry_run_makes_no_writes(self) -> None:
    self._mark_template_drift()
    skill_file = self.skill_dir / "SKILL.md"
    content_file = self.skill_dir / "content.md"
    pre_skill = skill_file.read_bytes()
    pre_content = content_file.read_bytes()

    buf = io.StringIO()
    with redirect_stdout(buf):
      code = cli.upgrade_skills_command(
        argparse.Namespace(dry_run=True, skill=None, yes=False)
      )

    self.assertEqual(code, 0)
    self.assertIn("Planned SKILL.md regenerations", buf.getvalue())
    self.assertEqual(skill_file.read_bytes(), pre_skill)
    self.assertEqual(content_file.read_bytes(), pre_content)

  def test_upgrade_respects_skill_flag(self) -> None:
    self._mark_template_drift()
    buf = io.StringIO()
    with redirect_stdout(buf):
      code = cli.upgrade_skills_command(
        argparse.Namespace(
          dry_run=True,
          skill="bill-does-not-exist",
          yes=False,
        )
      )
    self.assertEqual(code, 0)
    self.assertIn("No skills with template_version drift", buf.getvalue())

  def test_upgrade_requires_yes_confirmation(self) -> None:
    self._mark_template_drift()
    skill_file = self.skill_dir / "SKILL.md"
    pre_skill = skill_file.read_bytes()

    buf = io.StringIO()
    with redirect_stdout(buf):
      code = cli.upgrade_skills_command(
        argparse.Namespace(dry_run=False, skill=None, yes=False)
      )
    self.assertEqual(code, 0)
    self.assertIn("Re-run with --yes", buf.getvalue())
    self.assertEqual(skill_file.read_bytes(), pre_skill)

  def test_upgrade_never_touches_content_md(self) -> None:
    self._mark_template_drift()
    content_file = self.skill_dir / "content.md"
    original_content = content_file.read_bytes()

    cli.upgrade_skills_command(
      argparse.Namespace(dry_run=False, skill=None, yes=True)
    )
    self.assertEqual(content_file.read_bytes(), original_content)

  def test_upgrade_adds_project_overrides_when_previously_missing(self) -> None:
    """SKILL-21 follow-up: a platform-pack SKILL.md that pre-dates the
    template bump is missing ``## Project Overrides`` in its body.
    ``skill-bill upgrade`` regenerates it from the current template and
    must now include the ceremony heading alongside a reference to
    ``.agents/skill-overrides.md``.
    """
    skill_file = self.skill_dir / "SKILL.md"
    pre_missing_body = (
      "---\n"
      "name: bill-fixture-code-review\n"
      "description: Fixture.\n"
      f"shell_contract_version: 1.1\n"
      "template_version: 2020.01.01\n"
      "---\n"
      "\n"
      "## Description\nFixture.\n\n"
      "## Specialist Scope\nFixture.\n\n"
      "## Inputs\nFixture.\n\n"
      "## Outputs Contract\nFixture.\n\n"
      + CANONICAL_EXECUTION_BODY
      + "\n"
      "## Execution Mode Reporting\nFixture.\n\n"
      "## Telemetry Ceremony Hooks\nFixture.\n"
    )
    skill_file.write_text(pre_missing_body, encoding="utf-8")
    self.assertNotIn("## Project Overrides", skill_file.read_text(encoding="utf-8"))

    cli.upgrade_skills_command(
      argparse.Namespace(dry_run=False, skill=None, yes=True)
    )

    upgraded = skill_file.read_text(encoding="utf-8")
    self.assertIn("## Project Overrides", upgraded)
    self.assertIn(".agents/skill-overrides.md", upgraded)

  def test_upgrade_rolls_back_skill_md_on_validator_failure(self) -> None:
    """AC 8: validator failure during upgrade rolls SKILL.md back byte-for-byte.

    content.md must be untouched by ``skill-bill upgrade`` in every path,
    including the rollback path.
    """
    from skill_bill import shell_content_contract
    from skill_bill.shell_content_contract import InvalidExecutionSectionError

    self._mark_template_drift()
    skill_file = self.skill_dir / "SKILL.md"
    content_file = self.skill_dir / "content.md"
    pre_skill_bytes = skill_file.read_bytes()
    pre_content_bytes = content_file.read_bytes()

    def boom(*_args, **_kwargs) -> None:
      raise InvalidExecutionSectionError("simulated validator failure")

    buf = io.StringIO()
    with mock.patch.object(shell_content_contract, "assert_execution_body_matches", side_effect=boom):
      with redirect_stderr(buf):
        code = cli.upgrade_skills_command(
          argparse.Namespace(dry_run=False, skill=None, yes=True)
        )

    self.assertEqual(code, 5)
    self.assertIn("Rolled back upgrade", buf.getvalue())
    self.assertEqual(skill_file.read_bytes(), pre_skill_bytes)
    self.assertEqual(content_file.read_bytes(), pre_content_bytes)


class DoctorCommandTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = Path(self._tmpdir.name) / "repo"
    self.repo.mkdir()
    (self.repo / "skills").mkdir()
    self.skill_dir = _seed_governed_skill(self.repo)
    self._patcher = mock.patch.object(cli, "_resolve_repo_root_for_cli", return_value=self.repo)
    self._patcher.start()
    self.addCleanup(self._patcher.stop)

  def _invoke_doctor(self) -> dict:
    buf = io.StringIO()
    with redirect_stdout(buf):
      cli.doctor_command(argparse.Namespace(db=None, format="json"))
    return json.loads(buf.getvalue())

  def test_doctor_reports_missing_content_md_as_error(self) -> None:
    (self.skill_dir / "content.md").unlink()
    payload = self._invoke_doctor()
    missing = payload["content_md_missing"]
    self.assertEqual(len(missing), 1)
    self.assertEqual(missing[0]["severity"], "error")
    self.assertEqual(missing[0]["skill_name"], "bill-fixture-code-review")

  def test_doctor_reports_template_drift_with_upgrade_command(self) -> None:
    skill_file = self.skill_dir / "SKILL.md"
    text = skill_file.read_text(encoding="utf-8")
    text = text.replace(
      f"template_version: {TEMPLATE_VERSION}\n",
      "template_version: 2020.01.01\n",
    )
    skill_file.write_text(text, encoding="utf-8")

    payload = self._invoke_doctor()
    drift = payload["template_version_drift"]
    self.assertEqual(len(drift), 1)
    self.assertEqual(drift[0]["severity"], "warning")
    self.assertEqual(drift[0]["skill_name"], "bill-fixture-code-review")
    self.assertIn("skill-bill upgrade", drift[0]["action"])

  def test_doctor_clean_state_reports_no_issues(self) -> None:
    payload = self._invoke_doctor()
    self.assertEqual(payload["content_md_missing"], [])
    self.assertEqual(payload["template_version_drift"], [])


class ResolveRepoRootFallbackTest(unittest.TestCase):
  """F-001: verify the CWD fallback branch runs without NameError.

  ``_resolve_repo_root_for_cli`` previously imported ``Path`` inside a nested
  function scope, so the ``Path.cwd().resolve()`` fallback raised
  ``NameError`` when reached. Tests that mocked the resolver masked this bug,
  so this test exercises the real resolver with a CWD where neither
  ``skills/`` nor ``platform-packs/`` exists under the package's
  ``_REPO_ROOT``.
  """

  def test_doctor_command_falls_back_to_cwd_cleanly(self) -> None:
    from skill_bill import scaffold

    with tempfile.TemporaryDirectory() as repo_root_dir, tempfile.TemporaryDirectory() as cwd_dir:
      repo_root = Path(repo_root_dir)
      cwd = Path(cwd_dir)
      _seed_governed_skill(cwd)

      original_cwd = os.getcwd()
      os.chdir(cwd)
      try:
        with mock.patch.object(scaffold, "_REPO_ROOT", repo_root):
          resolved = cli._resolve_repo_root_for_cli()
          self.assertEqual(resolved, cwd.resolve())

          buf = io.StringIO()
          with redirect_stdout(buf):
            code = cli.doctor_command(argparse.Namespace(db=None, format="json"))
          self.assertEqual(code, 0)
          payload = json.loads(buf.getvalue())
          self.assertIn("content_md_missing", payload)
          self.assertIn("template_version_drift", payload)
      finally:
        os.chdir(original_cwd)


if __name__ == "__main__":
  unittest.main()
