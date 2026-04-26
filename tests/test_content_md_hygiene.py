from __future__ import annotations

from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.shell_content_contract import CEREMONY_FREE_FORM_H2S  # noqa: E402
from scripts.skill_repo_contracts import (  # noqa: E402
  ADDON_SUPPORTING_FILE_TARGETS,
  required_supporting_files_for_skill,
)


FRAMEWORK_DUPLICATION_LINES = (
  "## Additional Resources",
  "## Output Rules",
  "## Review Output",
  "## Output Format",
  "### Telemetry",
  "### Implementation Mode Notes",
)

SYSTEM_OWNED_CONTENT_MARKERS = (
  "## Setup",
)

EXECUTION_AND_REPORTING_CEREMONY_MARKERS = (
  "shared execution-mode contract",
  "If execution mode is `inline`:",
  "If execution mode is `delegated`:",
  "delegated subagent",
  "wrapper-linked sidecars",
  "Selected add-ons: none",
  "When reporting results:",
  "show issue count by category",
  "report each fix with `file:line`",
  "display the final `./gradlew check` result",
)


class ContentMdHygieneTest(unittest.TestCase):
  def test_governed_content_files_do_not_reintroduce_ceremony_headings(self) -> None:
    for content_file in sorted((ROOT / "platform-packs").rglob("content.md")):
      text = content_file.read_text(encoding="utf-8")
      lines = text.splitlines()
      for heading in CEREMONY_FREE_FORM_H2S:
        with self.subTest(content_file=content_file, heading=heading):
          self.assertNotIn(
            heading,
            lines,
            f"{content_file} must not reintroduce scaffolder-owned ceremony heading '{heading}'.",
          )

  def test_skills_content_files_do_not_reintroduce_ceremony_headings(self) -> None:
    for content_file in sorted((ROOT / "skills").rglob("content.md")):
      text = content_file.read_text(encoding="utf-8")
      lines = text.splitlines()
      for heading in CEREMONY_FREE_FORM_H2S:
        with self.subTest(content_file=content_file, heading=heading):
          self.assertNotIn(
            heading,
            lines,
            f"{content_file} must not reintroduce scaffolder-owned ceremony heading '{heading}'.",
          )

  def test_governed_content_files_do_not_inline_shared_review_contract_blocks(self) -> None:
    for content_file in sorted((ROOT / "platform-packs").rglob("content.md")):
      lines = content_file.read_text(encoding="utf-8").splitlines()
      for marker in FRAMEWORK_DUPLICATION_LINES:
        with self.subTest(content_file=content_file, marker=marker):
          self.assertNotIn(
            marker,
            lines,
            f"{content_file} must not inline shared review-contract block '{marker}'.",
          )

  def test_governed_content_files_do_not_reference_required_supporting_files(self) -> None:
    for content_file in sorted((ROOT / "platform-packs").rglob("content.md")):
      text = content_file.read_text(encoding="utf-8")
      skill_name = content_file.parent.name
      required_files = required_supporting_files_for_skill(skill_name)
      for file_name in required_files:
        if file_name in ADDON_SUPPORTING_FILE_TARGETS:
          continue
        with self.subTest(content_file=content_file, file_name=file_name):
          self.assertNotIn(
            file_name,
            text,
            f"{content_file} must not carry system-required supporting file reference '{file_name}'; keep required sidecar references in SKILL.md.",
          )

  def test_governed_content_files_do_not_inline_setup_contract(self) -> None:
    for content_file in sorted((ROOT / "platform-packs").rglob("content.md")):
      text = content_file.read_text(encoding="utf-8")
      for marker in SYSTEM_OWNED_CONTENT_MARKERS:
        with self.subTest(content_file=content_file, marker=marker):
          self.assertNotIn(
            marker,
            text,
            f"{content_file} must not inline system-owned setup contract marker '{marker}'; keep it in SKILL.md.",
          )

  def test_governed_content_files_do_not_inline_execution_or_reporting_ceremony(self) -> None:
    for content_file in sorted((ROOT / "platform-packs").rglob("content.md")):
      text = content_file.read_text(encoding="utf-8")
      for marker in EXECUTION_AND_REPORTING_CEREMONY_MARKERS:
        with self.subTest(content_file=content_file, marker=marker):
          self.assertNotIn(
            marker,
            text,
            f"{content_file} must not inline shell-owned execution/reporting ceremony marker '{marker}'.",
          )


if __name__ == "__main__":
  unittest.main()
