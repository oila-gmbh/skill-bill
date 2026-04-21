from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.cli import main  # noqa: E402
from skill_bill.shell_content_contract import PyYAMLMissingError  # noqa: E402
from skill_bill.upgrade import upgrade_skill_wrappers  # noqa: E402


_GOVERNED_FRONTMATTER = """\
---
name: {name}
description: Fixture content.
---
"""

_OLD_PROJECT_OVERRIDES = """\
## Project Overrides

Legacy project override text that should be collapsed by upgrade.

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read it.
"""

_SETUP_SECTION = """\
## Setup

Fixture setup text.
"""

_SHELL_CEREMONY = """\
## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read it and apply it as the highest-priority instruction for this skill.

## Inputs

Fixture shell ceremony inputs.

## Execution Mode Reporting

Execution mode: inline | delegated

## Telemetry Ceremony Hooks

Follow `telemetry-contract.md` when it is present.
"""


def _governed_skill_body(
  *,
  name: str,
  family: str,
  description: str,
  area: str = "",
) -> str:
  del family
  area_scope = "Scoped to one approved code-review area." if area else "Baseline orchestrator for platform review."
  return (
    _GOVERNED_FRONTMATTER.format(name=name)
    + "\n"
    + "# Legacy Governed Wrapper\n\n"
    + _OLD_PROJECT_OVERRIDES
    + "\n"
    + f"## Description\n\n{description}\n\n"
    + f"## Specialist Scope\n\n{area_scope}\n\n"
    + "## Inputs\n\nLegacy inputs.\n\n"
    + "## Outputs Contract\n\nLegacy outputs.\n\n"
    + "## Execution Mode Reporting\n\nExecution mode: inline | delegated\n\n"
    + "## Telemetry Ceremony Hooks\n\nFollow `telemetry-contract.md` when it is present.\n\n"
    + "## Old Boilerplate\n\n"
    + "This stale wrapper section should be removed by upgrade.\n"
  )


def _build_upgrade_repo(tmp_path: Path) -> Path:
  repo = tmp_path / "repo"
  scripts_dir = repo / "scripts"
  scripts_dir.mkdir(parents=True, exist_ok=True)
  (scripts_dir / "validate_agent_configs.py").write_text(
    (ROOT / "scripts" / "validate_agent_configs.py").read_text(encoding="utf-8"),
    encoding="utf-8",
  )
  (scripts_dir / "skill_repo_contracts.py").write_text(
    (ROOT / "scripts" / "skill_repo_contracts.py").read_text(encoding="utf-8"),
    encoding="utf-8",
  )
  shell_ceremony = repo / "orchestration" / "shell-content-contract" / "shell-ceremony.md"
  shell_ceremony.parent.mkdir(parents=True, exist_ok=True)
  shell_ceremony.write_text(_SHELL_CEREMONY, encoding="utf-8")

  horizontal_skill = repo / "skills" / "bill-horizontal-test"
  horizontal_skill.mkdir(parents=True, exist_ok=True)
  (horizontal_skill / "shell-ceremony.md").symlink_to(shell_ceremony)
  (horizontal_skill / "SKILL.md").write_text(
    "---\n"
    "name: bill-horizontal-test\n"
    "description: Fixture horizontal skill.\n"
    "---\n\n"
    "# Fixture Horizontal Skill\n\n"
    f"{_OLD_PROJECT_OVERRIDES}\n"
    f"{_SETUP_SECTION}",
    encoding="utf-8",
  )

  pack_root = repo / "platform-packs" / "kotlin"
  pack_root.mkdir(parents=True, exist_ok=True)
  (pack_root / "platform.yaml").write_text(
    """\
platform: kotlin
contract_version: "1.1"
display_name: Kotlin

routing_signals:
  strong:
    - ".kt"
  tie_breakers:
    - "fixture tie-breaker"

declared_code_review_areas:
  - architecture

declared_files:
  baseline: code-review/bill-kotlin-code-review/SKILL.md
  areas:
    architecture: code-review/bill-kotlin-code-review-architecture/SKILL.md

area_metadata:
  architecture:
    focus: "architecture, boundaries, and dependency direction"
""",
    encoding="utf-8",
  )

  baseline_dir = pack_root / "code-review" / "bill-kotlin-code-review"
  baseline_dir.mkdir(parents=True, exist_ok=True)
  (baseline_dir / "SKILL.md").write_text(
    _governed_skill_body(
      name="bill-kotlin-code-review",
      family="code-review",
      description="Use when reviewing Kotlin changes across code-review specialists.",
    ),
    encoding="utf-8",
  )
  (baseline_dir / "content.md").write_text("# Content\n\nUnchanged baseline content.\n", encoding="utf-8")
  (baseline_dir / "shell-ceremony.md").symlink_to(shell_ceremony)

  area_dir = pack_root / "code-review" / "bill-kotlin-code-review-architecture"
  area_dir.mkdir(parents=True, exist_ok=True)
  (area_dir / "SKILL.md").write_text(
    _governed_skill_body(
      name="bill-kotlin-code-review-architecture",
      family="code-review",
      area="architecture",
      description="Use when reviewing Kotlin changes for architecture, boundaries, and dependency direction.",
    ),
    encoding="utf-8",
  )
  (area_dir / "content.md").write_text("# Content\n\nUnchanged area content.\n", encoding="utf-8")
  (area_dir / "shell-ceremony.md").symlink_to(shell_ceremony)
  return repo


def _top_level_h2_headings(text: str) -> list[str]:
  return [line.strip() for line in text.splitlines() if line.startswith("## ")]


class UpgradeCliTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = _build_upgrade_repo(Path(self._tmpdir.name))

  def test_upgrade_regenerates_wrappers_without_touching_sidecars(self) -> None:
    baseline_skill = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "SKILL.md"
    )
    baseline_content = baseline_skill.with_name("content.md")
    baseline_ceremony = baseline_skill.with_name("shell-ceremony.md")
    content_before = baseline_content.read_bytes()
    ceremony_before = baseline_ceremony.read_bytes()

    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
    ):
      exit_code = main(["upgrade", "--repo-root", str(self.repo), "--format", "json"])

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertGreaterEqual(payload["regenerated_count"], 2)
    self.assertFalse(payload["content_md_touched"])
    self.assertFalse(payload["shell_ceremony_touched"])

    upgraded_body = baseline_skill.read_text(encoding="utf-8")
    self.assertEqual(
      _top_level_h2_headings(upgraded_body),
      ["## Descriptor", "## Execution", "## Ceremony"],
    )
    self.assertNotIn("## Old Boilerplate", upgraded_body)
    self.assertEqual(content_before, baseline_content.read_bytes())
    self.assertEqual(ceremony_before, baseline_ceremony.read_bytes())

    horizontal_skill = self.repo / "skills" / "bill-horizontal-test" / "SKILL.md"
    horizontal_body = horizontal_skill.read_text(encoding="utf-8")
    self.assertIn("Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).", horizontal_body)
    self.assertNotIn("Legacy project override text", horizontal_body)

  def test_upgrade_rolls_back_when_validator_fails(self) -> None:
    baseline_skill = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "SKILL.md"
    )
    horizontal_skill = self.repo / "skills" / "bill-horizontal-test" / "SKILL.md"
    baseline_before = baseline_skill.read_bytes()
    horizontal_before = horizontal_skill.read_bytes()

    with mock.patch("skill_bill.upgrade._run_validator", side_effect=RuntimeError("boom")):
      with self.assertRaises(RuntimeError):
        upgrade_skill_wrappers(self.repo)

    self.assertEqual(baseline_before, baseline_skill.read_bytes())
    self.assertEqual(horizontal_before, horizontal_skill.read_bytes())

  def test_render_alias_accepts_targeted_skill_name(self) -> None:
    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
    ):
      exit_code = main(
        [
          "render",
          "--repo-root",
          str(self.repo),
          "--skill-name",
          "bill-kotlin-code-review",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["regenerated_count"], 1)
    self.assertEqual(
      payload["regenerated_files"],
      [
        str(
          (
            self.repo
            / "platform-packs"
            / "kotlin"
            / "code-review"
            / "bill-kotlin-code-review"
            / "SKILL.md"
          ).resolve()
        )
      ],
    )


class AuthoringCliTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = _build_upgrade_repo(Path(self._tmpdir.name))

  def test_list_reports_completion_status_and_generation_drift(self) -> None:
    baseline_content = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    )
    baseline_content.write_text(
      "# Content\n\n## Purpose\n\nDocument the baseline review flow.\n",
      encoding="utf-8",
    )

    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(
        ["list", "--repo-root", str(self.repo), "--format", "json"]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    baseline_entry = next(
      entry for entry in payload["skills"] if entry["skill_name"] == "bill-kotlin-code-review"
    )
    self.assertEqual(baseline_entry["completion_status"], "complete")
    self.assertTrue(baseline_entry["generation_drift"])

  def test_edit_guided_updates_section_and_regenerates_wrapper(self) -> None:
    target_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
    )
    content_file = target_dir / "content.md"
    content_file.write_text(
      "# Content\n\n## Purpose\n\nCurrent purpose.\n\n## Constraints\n\nTODO: fill this in.\n",
      encoding="utf-8",
    )

    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
      mock.patch(
        "builtins.input",
        side_effect=[
          "r",
          "Updated purpose from guided editing.",
          ".done",
          "d",
        ],
      ),
    ):
      exit_code = main(
        [
          "edit",
          "bill-kotlin-code-review",
          "--repo-root",
          str(self.repo),
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    output = stdout.getvalue()
    payload = json.loads(output[output.index("{"):])
    self.assertEqual(payload["skill_name"], "bill-kotlin-code-review")
    self.assertEqual(payload["completion_status"], "draft")
    self.assertIn("Updated purpose from guided editing.", content_file.read_text(encoding="utf-8"))
    self.assertTrue(payload["wrapper_regenerated"])

  def test_validate_selected_skill_reports_todo_failure(self) -> None:
    content_file = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    )
    content_file.write_text("# Content\n\nTODO: unresolved\n", encoding="utf-8")

    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(
        [
          "validate",
          "--repo-root",
          str(self.repo),
          "--skill-name",
          "bill-kotlin-code-review",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 1)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["status"], "fail")
    self.assertTrue(
      any("unresolved TODO/FIXME placeholder" in issue for issue in payload["issues"])
    )

  def test_show_reports_section_status_and_recommended_commands(self) -> None:
    content_file = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    )
    content_file.write_text(
      "# Review Content\n\n## Review Focus\n\nCurrent focus.\n\n## Review Guidance\n\nTODO: add guidance.\n",
      encoding="utf-8",
    )

    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(
        [
          "show",
          "bill-kotlin-code-review",
          "--repo-root",
          str(self.repo),
          "--content",
          "none",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["completion_status"], "draft")
    self.assertEqual(
      [section["heading"] for section in payload["sections"]],
      ["Review Focus", "Review Guidance"],
    )
    self.assertTrue(
      any(command.startswith("skill-bill edit bill-kotlin-code-review") for command in payload["recommended_commands"])
    )

  def test_fill_updates_single_section_and_regenerates_wrapper(self) -> None:
    target_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
    )
    content_file = target_dir / "content.md"
    content_file.write_text(
      "# Review Content\n\n## Review Focus\n\nCurrent focus.\n\n## Review Guidance\n\nTODO: fill this in.\n",
      encoding="utf-8",
    )

    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
    ):
      exit_code = main(
        [
          "fill",
          "bill-kotlin-code-review",
          "--repo-root",
          str(self.repo),
          "--section",
          "Review Guidance",
          "--body",
          "- Check auth flows and secret handling.",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["updated_section"], "Review Guidance")
    rendered = content_file.read_text(encoding="utf-8")
    self.assertIn("- Check auth flows and secret handling.", rendered)
    self.assertIn("## Review Focus", rendered)
    self.assertIn("Current focus.", rendered)
    self.assertTrue(payload["wrapper_regenerated"])

  def test_edit_can_target_single_section(self) -> None:
    target_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
    )
    content_file = target_dir / "content.md"
    content_file.write_text(
      "# Review Content\n\n## Review Focus\n\nLeave me alone.\n\n## Review Guidance\n\nOld guidance.\n",
      encoding="utf-8",
    )

    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
      mock.patch(
        "builtins.input",
        side_effect=[
          "r",
          "Updated targeted guidance.",
          ".done",
        ],
      ),
    ):
      exit_code = main(
        [
          "edit",
          "bill-kotlin-code-review",
          "--repo-root",
          str(self.repo),
          "--section",
          "Review Guidance",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    output = stdout.getvalue()
    payload = json.loads(output[output.index("{"):])
    self.assertEqual(payload["updated_section"], "Review Guidance")
    rendered = content_file.read_text(encoding="utf-8")
    self.assertIn("Updated targeted guidance.", rendered)
    self.assertIn("## Review Focus", rendered)
    self.assertIn("Leave me alone.", rendered)

  def test_doctor_skill_reports_plain_language_diagnosis(self) -> None:
    content_file = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    )
    content_file.write_text("# Review Content\n\nTODO: unresolved\n", encoding="utf-8")

    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(
        [
          "doctor",
          "skill",
          "bill-kotlin-code-review",
          "--repo-root",
          str(self.repo),
          "--content",
          "none",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 1)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["status"], "fail")
    self.assertIn("Validation found contract or content issues", payload["diagnosis"])
    self.assertTrue(
      any(command.startswith("skill-bill edit bill-kotlin-code-review") for command in payload["recommended_commands"])
    )

  def test_create_and_fill_scaffolds_one_skill_and_writes_authored_body(self) -> None:
    payload_path = self.repo / "payload.json"
    payload_path.write_text(
      json.dumps(
        {
          "scaffold_payload_version": "1.0",
          "kind": "code-review-area",
          "platform": "kotlin",
          "area": "security",
        }
      ),
      encoding="utf-8",
    )
    body_path = self.repo / "body.md"
    body_path.write_text(
      "## Focus\n\nReview Kotlin security-sensitive changes.\n\n"
      "## Review Triggers\n\n- Auth, tokens, secrets.\n\n"
      "## Review Guidance\n\n- Check secret handling and auth boundaries.\n",
      encoding="utf-8",
    )
    upgrade_skill_wrappers(self.repo, validate=False)

    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("pathlib.Path.cwd", return_value=self.repo),
      mock.patch("skill_bill.scaffold._run_validator", return_value=None),
      mock.patch("skill_bill.scaffold._perform_install", return_value=([], [])),
      mock.patch("skill_bill.upgrade._run_validator", return_value=None),
      mock.patch(
        "skill_bill.config.load_telemetry_settings",
        return_value=SimpleNamespace(level="anonymous"),
      ),
    ):
      exit_code = main(
        [
          "create-and-fill",
          "--payload",
          str(payload_path),
          "--body-file",
          str(body_path),
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["scaffold"]["skill_name"], "bill-kotlin-code-review-security")
    self.assertEqual(payload["authoring"]["completion_status"], "complete")
    content_file = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-security"
      / "content.md"
    )
    self.assertIn("Review Kotlin security-sensitive changes.", content_file.read_text(encoding="utf-8"))


class NewAddonCliTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.repo = Path(self._tmpdir.name) / "repo"
    pack_root = self.repo / "platform-packs" / "kmp"
    pack_root.mkdir(parents=True, exist_ok=True)
    (pack_root / "platform.yaml").write_text(
      """\
platform: kmp
contract_version: "1.1"
display_name: KMP

routing_signals:
  strong:
    - "androidMain"
  tie_breakers:
    - "prefer KMP for multiplatform fixtures"

declared_code_review_areas:
  - ui

declared_files:
  baseline: code-review/bill-kmp-code-review/SKILL.md
  areas:
    ui: code-review/bill-kmp-code-review-ui/SKILL.md

area_metadata:
  ui:
    focus: "UI correctness and framework usage"
""",
      encoding="utf-8",
    )

  def test_new_addon_writes_markdown_body_to_pack_addons_dir(self) -> None:
    stdout = io.StringIO()
    with (
      contextlib.redirect_stdout(stdout),
      mock.patch("pathlib.Path.cwd", return_value=self.repo),
      mock.patch("skill_bill.scaffold._run_validator", return_value=None),
      mock.patch("skill_bill.scaffold._perform_install", return_value=([], [])),
      mock.patch(
        "skill_bill.config.load_telemetry_settings",
        return_value=SimpleNamespace(level="anonymous"),
      ),
    ):
      exit_code = main(
        [
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-compose",
          "--body",
          "# android-compose\n\nUse this add-on.\n",
          "--format",
          "json",
        ]
      )

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["kind"], "add-on")
    addon_path = self.repo / "platform-packs" / "kmp" / "addons" / "android-compose.md"
    self.assertEqual(Path(payload["skill_path"]).resolve(), addon_path.parent.resolve())
    self.assertEqual(
      addon_path.read_text(encoding="utf-8"),
      "# android-compose\n\nUse this add-on.\n",
    )

  def test_new_addon_requires_body_source_when_not_interactive(self) -> None:
    stderr = io.StringIO()
    with contextlib.redirect_stderr(stderr):
      exit_code = main(
        [
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-compose",
        ]
      )

    self.assertEqual(exit_code, 1)
    self.assertIn("Provide exactly one of --body or --body-file", stderr.getvalue())

  def test_new_addon_surfaces_contract_errors_without_traceback(self) -> None:
    stderr = io.StringIO()
    with (
      contextlib.redirect_stderr(stderr),
      mock.patch(
        "skill_bill.scaffold.scaffold",
        side_effect=PyYAMLMissingError("PyYAML is required to load platform packs."),
      ),
    ):
      exit_code = main(
        [
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-compose",
          "--body",
          "# android-compose\n\nUse this add-on.\n",
        ]
      )

    self.assertEqual(exit_code, 1)
    self.assertEqual(stderr.getvalue().strip(), "PyYAML is required to load platform packs.")
