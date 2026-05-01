from __future__ import annotations

from pathlib import Path
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
scripts_dir = ROOT / "scripts"
if str(scripts_dir) not in sys.path:
  sys.path.insert(0, str(scripts_dir))

from scripts.validate_agent_configs import (  # noqa: E402
  validate_editorial_workflow_skills,
  validate_skill_file,
)


EDITORIAL_SKILL_DIR = ROOT / "skills" / "bill-editorial-assignment-desk"


class EditorialWorkflowContractTest(unittest.TestCase):
  maxDiff = None

  def test_valid_editorial_skill_scaffold_validates(self) -> None:
    issues: list[str] = []

    validate_skill_file(
      "bill-editorial-assignment-desk",
      EDITORIAL_SKILL_DIR / "SKILL.md",
      issues,
    )
    validate_editorial_workflow_skills(ROOT, issues)

    self.assertEqual([], issues)

  def test_rejects_missing_required_editorial_skill_sections(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      temp_root = Path(temp_dir)
      target_dir = temp_root / "skills" / "bill-editorial-assignment-desk"
      shutil.copytree(EDITORIAL_SKILL_DIR, target_dir, symlinks=True)
      content_file = target_dir / "content.md"
      content_file.write_text(
        content_file.read_text(encoding="utf-8").replace(
          "## Candidate Selection Pause",
          "## Candidate Selection Removed",
        ),
        encoding="utf-8",
      )

      issues: list[str] = []
      validate_editorial_workflow_skills(temp_root, issues)

    self.assertIn(
      f"{target_dir}: editorial workflow skill must include '## Candidate Selection Pause'",
      issues,
    )

  def test_source_check_output_contract_markers_are_pinned(self) -> None:
    reference = (EDITORIAL_SKILL_DIR / "reference.md").read_text(encoding="utf-8")

    for marker in (
      "source_verification_contract_v1",
      "confirmed_fact",
      "reputable_reporting",
      "community_claim",
      "rumor",
      "leak",
      "speculation",
      "unsupported_claims",
      "missing_primary_sources",
    ):
      self.assertIn(marker, reference)

  def test_readian_mcp_install_setup_is_pinned(self) -> None:
    skill = (EDITORIAL_SKILL_DIR / "SKILL.md").read_text(encoding="utf-8")
    content = (EDITORIAL_SKILL_DIR / "content.md").read_text(encoding="utf-8")
    combined = f"{skill}\n{content}"

    for marker in (
      "Node.js 18+",
      "Java 21+",
      "npm install -g @readian/mcp-client",
      "readian-mcp status",
      "\"mcpServers\"",
      "\"args\": [\"stdio\"]",
      "which readian-mcp",
      "readian-mcp login --identifier <my-readian-username-or-email>",
      "Do not publish, tag, or release",
      "Trusted Publishing",
    ):
      self.assertIn(marker, combined)

  def test_editorial_intake_requires_story_intent_and_execution_language(self) -> None:
    skill = (EDITORIAL_SKILL_DIR / "SKILL.md").read_text(encoding="utf-8")
    content = (EDITORIAL_SKILL_DIR / "content.md").read_text(encoding="utf-8")
    combined = f"{skill}\n{content}"

    for marker in (
      "what the journalist wants to write about today",
      "execution language",
      "story_intent",
      "execution_language",
      "Do not start with a broad feed by default",
      "Do not fetch a broad feed by default",
      "Call `readian_get_spotlight` only",
      "Write the story pack in `editorial_profile.execution_language`",
    ):
      self.assertIn(marker, combined)

  def test_candidate_ranking_output_contract_markers_are_pinned(self) -> None:
    reference = (EDITORIAL_SKILL_DIR / "reference.md").read_text(encoding="utf-8")

    for marker in (
      "candidate_ranking_contract_v1",
      "newsworthiness",
      "timeliness",
      "source_confidence",
      "audience_fit",
      "angle_strength",
      "coverage_gap",
      "social_signal",
      "effort",
      "risk",
    ):
      self.assertIn(marker, reference)


if __name__ == "__main__":
  unittest.main()
