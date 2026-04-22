"""SKILL-21 AC 15(c): migration script coverage.

The migration script rewrites governed SKILL.md files under shelled
families, moves author prose into a new sibling ``content.md``, and
regenerates the shell. Tests here exercise idempotency, byte-match
(``--strict``), per-skill rollback on validator failure, the automatic
``_migration_backup/<timestamp>/`` directory, and the summary + exit
codes.
"""

from __future__ import annotations

import contextlib
import io
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))


import migrate_to_content_md  # noqa: E402
from skill_bill.shell_content_contract import InvalidExecutionSectionError  # noqa: E402


KOTLIN_MANIFEST = """\
platform: kotlin
contract_version: "1.0"
display_name: Kotlin
governs_addons: false

routing_signals:
  strong:
    - ".kt"
  tie_breakers: []
  addon_signals: []

declared_code_review_areas:
  - architecture

declared_files:
  baseline: code-review/bill-kotlin-code-review/SKILL.md
  areas:
    architecture: code-review/bill-kotlin-code-review-architecture/SKILL.md
"""

V1_0_SKILL_MD = """\
---
name: bill-kotlin-code-review
description: Fixture legacy shell.
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Description
Author-edited Kotlin review description.

## Specialist Scope
Fixture specialist scope.

## Inputs
Fixture inputs.

## Outputs Contract
Fixture outputs contract.

## Execution Mode Reporting
Fixture execution mode reporting.

## Telemetry Ceremony Hooks
Fixture telemetry hooks.

## Free Form Setup
This section carries the author's free-form body that must move to
content.md after migration.

## Second Free Form
Another author-owned section.
"""

V1_0_AREA_SKILL_MD = """\
---
name: bill-kotlin-code-review-architecture
description: Fixture legacy area.
---

## Description
Use when reviewing Kotlin architecture.

## Specialist Scope
Fixture specialist scope.

## Inputs
Fixture inputs.

## Outputs Contract
Fixture outputs contract.

## Execution Mode Reporting
Fixture execution mode reporting.

## Telemetry Ceremony Hooks
Fixture telemetry hooks.

## Free Form Only
Author's free-form body.
"""


def _seed_v1_0_kotlin_repo(tmp_path: Path) -> Path:
  repo = tmp_path / "repo"
  repo.mkdir(parents=True)
  (repo / "skills").mkdir()
  pack = repo / "platform-packs" / "kotlin"
  pack.mkdir(parents=True)
  (pack / "platform.yaml").write_text(KOTLIN_MANIFEST, encoding="utf-8")

  baseline = pack / "code-review" / "bill-kotlin-code-review"
  baseline.mkdir(parents=True)
  (baseline / "SKILL.md").write_text(V1_0_SKILL_MD, encoding="utf-8")

  area = pack / "code-review" / "bill-kotlin-code-review-architecture"
  area.mkdir(parents=True)
  (area / "SKILL.md").write_text(V1_0_AREA_SKILL_MD, encoding="utf-8")
  return repo


class MigrationCoverageTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _seed_v1_0_kotlin_repo(self.tmp_path)

  def test_migration_moves_free_form_into_content_md(self) -> None:
    report = migrate_to_content_md.migrate(
      self.repo, force=False, strict=False, yes=True
    )
    successes = [entry for entry in report.migrations if entry.status == "migrated"]
    self.assertEqual(len(successes), 2)
    baseline_content = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertIn("Free Form Setup", baseline_content)
    self.assertIn("Second Free Form", baseline_content)
    # Author-edited Description must also flow into content.md because the
    # body differs from the scaffolder default.
    self.assertIn(
      "Author-edited Kotlin review description",
      baseline_content,
    )

  def test_migration_is_idempotent_second_run_skips(self) -> None:
    first = migrate_to_content_md.migrate(
      self.repo, force=False, strict=False, yes=True
    )
    self.assertTrue(any(entry.status == "migrated" for entry in first.migrations))
    second = migrate_to_content_md.migrate(
      self.repo, force=False, strict=False, yes=True
    )
    self.assertTrue(
      all(entry.status == "skipped" for entry in second.migrations),
      [entry.status for entry in second.migrations],
    )

  def test_force_overwrites_existing_content_md(self) -> None:
    migrate_to_content_md.migrate(
      self.repo, force=False, strict=False, yes=True
    )
    # Mutate content.md out-of-band; --force should overwrite.
    content_path = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    )
    content_path.write_text("OUT-OF-BAND CONTENT\n", encoding="utf-8")
    report = migrate_to_content_md.migrate(
      self.repo, force=True, strict=False, yes=True
    )
    self.assertTrue(any(entry.status == "migrated" for entry in report.migrations))
    # Force re-writes content.md from the current SKILL.md shape.
    new_body = content_path.read_text(encoding="utf-8")
    self.assertNotEqual(new_body.strip(), "OUT-OF-BAND CONTENT")

  def test_backup_directory_created_before_first_rewrite(self) -> None:
    report = migrate_to_content_md.migrate(
      self.repo, force=False, strict=False, yes=True
    )
    self.assertIsNotNone(report.backup_dir)
    backup_dir = report.backup_dir
    self.assertTrue(backup_dir.is_dir())
    # At least one SKILL.md backup should exist under the backup tree.
    backups = list(backup_dir.rglob("SKILL.md"))
    self.assertGreaterEqual(len(backups), 2)

  def test_per_skill_rollback_on_validator_failure(self) -> None:
    # Patch assert_execution_body_matches for the baseline only to simulate
    # a per-skill validation failure. The area skill should still migrate.
    original = migrate_to_content_md.assert_execution_body_matches

    def selective_asserter(skill_file: Path, *, context_label: str) -> None:
      if "bill-kotlin-code-review/" in str(skill_file):
        raise InvalidExecutionSectionError(
          f"simulated failure for {skill_file}"
        )
      return original(skill_file, context_label=context_label)

    with mock.patch.object(
      migrate_to_content_md,
      "assert_execution_body_matches",
      side_effect=selective_asserter,
    ):
      report = migrate_to_content_md.migrate(
        self.repo, force=False, strict=False, yes=True
      )

    statuses = {entry.skill_name: entry.status for entry in report.migrations}
    self.assertEqual(statuses["bill-kotlin-code-review"], "failed")
    self.assertEqual(statuses["bill-kotlin-code-review-architecture"], "migrated")

    # The baseline SKILL.md should be byte-identical to the v1.0 fixture
    # because its migration rolled back.
    baseline_skill = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "SKILL.md"
    )
    self.assertEqual(baseline_skill.read_text(encoding="utf-8"), V1_0_SKILL_MD)
    # content.md should NOT have been left behind.
    self.assertFalse(
      (baseline_skill.parent / "content.md").exists(),
      "content.md should have been rolled back with SKILL.md",
    )

  def test_final_summary_and_nonzero_exit_on_failure(self) -> None:
    original = migrate_to_content_md.assert_execution_body_matches

    def always_fail(skill_file: Path, *, context_label: str) -> None:
      raise Exception("simulated global failure")

    with mock.patch.object(
      migrate_to_content_md,
      "assert_execution_body_matches",
      side_effect=always_fail,
    ):
      stdout = io.StringIO()
      with contextlib.redirect_stdout(stdout):
        code = migrate_to_content_md.main(["--yes", "--repo-root", str(self.repo)])
    self.assertNotEqual(code, 0)
    _ = original

  def test_strict_mode_treats_byte_diff_as_edit(self) -> None:
    # Even with strict mode enabled, migration should still succeed because
    # the free-form sections and author-edited description always move into
    # content.md.
    report = migrate_to_content_md.migrate(
      self.repo, force=False, strict=True, yes=True
    )
    self.assertTrue(any(entry.status == "migrated" for entry in report.migrations))

  def test_migration_never_copies_project_overrides_ceremony(self) -> None:
    """Ceremony-leakage fix: `## Project Overrides` is shell governance.

    The v1.0 SKILL.md fixture above carries a ``## Project Overrides``
    section. The migration script MUST drop it on the floor rather than
    copy it into content.md — the regenerated SKILL.md emits it from
    the template instead.
    """
    migrate_to_content_md.migrate(self.repo, force=False, strict=False, yes=True)
    baseline_content = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("## Project Overrides", baseline_content)
    self.assertNotIn(".agents/skill-overrides.md", baseline_content)
    baseline_shell = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertIn("## Descriptor", baseline_shell)
    self.assertIn("[shell-ceremony.md](shell-ceremony.md)", baseline_shell)

  def test_migration_scrubs_ceremony_free_form_h2_blacklist(self) -> None:
    """Pass 2: free-form ceremony H2s never flow into content.md.

    The pass-1 fix dropped ``## Project Overrides`` because it is a
    required-section ceremony owned by the scaffolder. Pass 2 extends
    the scrub to a taxonomy blacklist of free-form shell ceremony
    (Setup, Additional Resources, Local Review Learnings, Output
    Format, Output Rules, Review Output, Delegated Mode, Inline Mode,
    Routing Rules, Shared Stack Detection, Execution Contract,
    Overview). All of these belong in SKILL.md or the shell runtime,
    never in the author-owned content.md.
    """
    blacklist_body = """\
## Setup

Generic scope determination bullet list.

## Additional Resources

- [stack-routing.md](stack-routing.md)

## Local Review Learnings

Apply only active learnings.

## Output Format

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

## Output Rules

Severity: Blocker | Major | Minor. Confidence: High | Medium | Low.

## Review Output

Review session ID: <id>

## Delegated Mode

Stub delegated mode.

## Inline Mode

Stub inline mode.

## Routing Rules

Stub routing rules.

## Shared Stack Detection

Stub shared stack detection.

## Execution Contract

Stub execution contract.

## Overview

Overview duplicates description.

## Author Section

Author-owned prose that must survive.
"""
    v1_0_with_blacklist = (
      "---\n"
      "name: bill-kotlin-code-review-architecture\n"
      "description: Fixture legacy area with ceremony leakage.\n"
      "---\n"
      "\n"
      "## Description\n"
      "Author-edited description.\n"
      "\n"
      "## Specialist Scope\n"
      "Fixture specialist scope.\n"
      "\n"
      "## Inputs\n"
      "Fixture inputs.\n"
      "\n"
      "## Outputs Contract\n"
      "Fixture outputs contract.\n"
      "\n"
      "## Execution Mode Reporting\n"
      "Fixture execution mode reporting.\n"
      "\n"
      "## Telemetry Ceremony Hooks\n"
      "Fixture telemetry hooks.\n"
      "\n"
      + blacklist_body
    )
    area_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-architecture"
    )
    (area_dir / "SKILL.md").write_text(v1_0_with_blacklist, encoding="utf-8")

    migrate_to_content_md.migrate(self.repo, force=True, strict=False, yes=True)

    area_content = (area_dir / "content.md").read_text(encoding="utf-8")
    blacklisted_headings = (
      "## Setup",
      "## Additional Resources",
      "## Local Review Learnings",
      "## Output Format",
      "## Output Rules",
      "## Review Output",
      "## Delegated Mode",
      "## Inline Mode",
      "## Routing Rules",
      "## Shared Stack Detection",
      "## Execution Contract",
      "## Overview",
    )
    for heading in blacklisted_headings:
      self.assertNotIn(
        heading,
        area_content,
        f"blacklisted heading {heading!r} leaked into content.md",
      )
    self.assertIn("## Author Section", area_content)
    self.assertIn("Author-owned prose that must survive", area_content)

  def test_migration_skips_required_sections_matching_current_template(self) -> None:
    """Ceremony-leakage fix: required H2 bodies that match the current
    scaffolder default are NOT copied into content.md.

    AC 11 step 3 of the pre-fix migration script compared the body against
    the pre-migration SKILL.md state and classified v1.0 template
    defaults as author edits, leaking them into content.md. The fix
    tightens the comparison to the current template's rendered output
    for each slot so unedited defaults stay out of content.md.
    """
    from skill_bill.scaffold_template import (
      ScaffoldTemplateContext,
      render_default_section,
    )

    context = ScaffoldTemplateContext(
      skill_name="bill-kotlin-code-review-architecture",
      family="code-review",
      platform="kotlin",
      area="architecture",
      display_name="Kotlin",
    )
    description_default = render_default_section("## Description", context)
    specialist_default = render_default_section("## Specialist Scope", context)
    inputs_default = render_default_section("## Inputs", context)
    outputs_default = render_default_section("## Outputs Contract", context)

    v1_0_area_with_template_defaults = (
      "---\n"
      "name: bill-kotlin-code-review-architecture\n"
      "description: Fixture area using current template defaults.\n"
      "---\n"
      "\n"
      + description_default
      + "\n"
      + specialist_default
      + "\n"
      + inputs_default
      + "\n"
      + outputs_default
      + "\n"
      "## Execution Mode Reporting\n"
      "Fixture execution mode reporting.\n"
      "\n"
      "## Telemetry Ceremony Hooks\n"
      "Fixture telemetry hooks.\n"
    )
    area_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-architecture"
    )
    (area_dir / "SKILL.md").write_text(v1_0_area_with_template_defaults, encoding="utf-8")

    migrate_to_content_md.migrate(self.repo, force=True, strict=False, yes=True)

    area_content = (area_dir / "content.md").read_text(encoding="utf-8")
    self.assertNotIn("## Description", area_content)
    self.assertNotIn("## Specialist Scope", area_content)
    self.assertNotIn("## Inputs", area_content)
    self.assertNotIn("## Outputs Contract", area_content)


if __name__ == "__main__":
  unittest.main()
