from __future__ import annotations

import unittest
from pathlib import Path

from skill_bill.install import discover_opencode_agent_mds as _discover_opencode_agent_mds


ROOT = Path(__file__).resolve().parents[1]
PLATFORM_PACKS_DIR = ROOT / "platform-packs"
SKILLS_DIR = ROOT / "skills"

REQUIRED_FRONTMATTER_FIELDS: tuple[str, ...] = ("description", "mode")

CLAUDE_ONLY_FORBIDDEN: tuple[str, ...] = (
  "Agent(subagent_type=",
  "subagent_type=",
  "@agent-",
  "Agent tool",
  "general-purpose",
)

EXPECTED_FEATURE_IMPLEMENT_STEMS: frozenset[str] = frozenset(
  {
    "bill-feature-implement-pre-planning",
    "bill-feature-implement-planning",
    "bill-feature-implement-implementation",
    "bill-feature-implement-implementation-fix",
    "bill-feature-implement-completeness-audit",
    "bill-feature-implement-quality-check",
    "bill-feature-implement-pr-description",
  }
)


def discover_opencode_agent_mds() -> list[Path]:
  return _discover_opencode_agent_mds(PLATFORM_PACKS_DIR, skills_root=SKILLS_DIR)


def parse_frontmatter(markdown: str, path: Path) -> tuple[dict[str, str], str]:
  lines = markdown.splitlines()
  if not lines or lines[0] != "---":
    return {}, markdown

  end_index: int | None = None
  for index, line in enumerate(lines[1:], start=1):
    if line == "---":
      end_index = index
      break
  if end_index is None:
    raise AssertionError(f"Unclosed frontmatter in {path}")

  frontmatter: dict[str, str] = {}
  for line in lines[1:end_index]:
    if not line.strip() or line.lstrip().startswith("#"):
      continue
    if ":" not in line:
      raise AssertionError(f"Invalid frontmatter line in {path}: {line}")
    key, value = line.split(":", 1)
    frontmatter[key.strip()] = value.strip().strip('"').strip("'")
  body = "\n".join(lines[end_index + 1 :])
  return frontmatter, body


class OpenCodeAgentsMdTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    self.md_paths = discover_opencode_agent_mds()
    self.assertGreater(
      len(self.md_paths),
      0,
      "Expected at least one opencode-agents/*.md file under platform-packs/ or skills/",
    )

  def test_each_markdown_agent_has_required_frontmatter_and_body(self) -> None:
    for md_path in self.md_paths:
      with self.subTest(markdown=str(md_path.relative_to(ROOT))):
        frontmatter, body = parse_frontmatter(md_path.read_text(encoding="utf-8"), md_path)
        for field in REQUIRED_FRONTMATTER_FIELDS:
          self.assertIn(field, frontmatter, f"missing field '{field}' in {md_path}")
          self.assertTrue(frontmatter[field].strip(), f"field '{field}' must be non-empty in {md_path}")
        self.assertEqual(frontmatter["mode"], "subagent")
        self.assertNotIn(
          "\n",
          frontmatter["description"],
          f"description frontmatter in {md_path} must be a single line",
        )
        self.assertTrue(body.strip(), f"markdown body must be non-empty in {md_path}")

  def test_name_frontmatter_matches_filename_stem(self) -> None:
    for md_path in self.md_paths:
      with self.subTest(markdown=str(md_path.relative_to(ROOT))):
        frontmatter, _ = parse_frontmatter(md_path.read_text(encoding="utf-8"), md_path)
        self.assertIn("name", frontmatter, f"missing 'name' frontmatter in {md_path}")
        self.assertEqual(
          frontmatter["name"],
          md_path.stem,
          f"name field must match filename stem for {md_path}",
        )

  def test_agent_names_are_unique_across_markdown_agents(self) -> None:
    seen: dict[str, Path] = {}
    for md_path in self.md_paths:
      frontmatter, _ = parse_frontmatter(md_path.read_text(encoding="utf-8"), md_path)
      name = frontmatter.get("name", md_path.stem)
      if name in seen:
        self.fail(
          f"Duplicate OpenCode agent name '{name}' in {md_path} and {seen[name]}",
        )
      seen[name] = md_path

  def test_markdown_agents_have_no_claude_only_references(self) -> None:
    for md_path in self.md_paths:
      with self.subTest(markdown=str(md_path.relative_to(ROOT))):
        content = md_path.read_text(encoding="utf-8")
        for forbidden in CLAUDE_ONLY_FORBIDDEN:
          self.assertNotIn(
            forbidden,
            content,
            f"{md_path} must not contain Claude-only token '{forbidden}'",
          )

  def test_markdown_agents_inline_specialist_contract(self) -> None:
    required_markers = (
      "Shared Contract For Every Specialist",
      "Shared Report Structure",
      "[F-001]",
    )
    for md_path in self.md_paths:
      if PLATFORM_PACKS_DIR not in md_path.parents:
        continue
      with self.subTest(markdown=str(md_path.relative_to(ROOT))):
        content = md_path.read_text(encoding="utf-8")
        for marker in required_markers:
          self.assertIn(
            marker,
            content,
            f"{md_path} must inline F-XXX contract marker '{marker}'",
          )

  def test_feature_implement_briefing_structural_shape(self) -> None:
    for md_path in self.md_paths:
      if md_path.stem not in EXPECTED_FEATURE_IMPLEMENT_STEMS:
        continue
      with self.subTest(markdown=str(md_path.relative_to(ROOT))):
        _, body = parse_frontmatter(md_path.read_text(encoding="utf-8"), md_path)
        self.assertRegex(
          body,
          r"\{[a-zA-Z_][a-zA-Z0-9_]*\}",
          f"body in {md_path} must contain at least one placeholder token like {{name}}",
        )
        has_result_block = "RESULT:" in body
        has_return_contract = "return contract" in body
        self.assertTrue(
          has_result_block or has_return_contract,
          f"body in {md_path} must contain a 'RESULT:' block or reference a 'return contract'",
        )

  def test_feature_implement_subagents_discovered_from_skills_root(self) -> None:
    discovered_stems = {p.stem for p in self.md_paths if SKILLS_DIR in p.parents}
    missing = EXPECTED_FEATURE_IMPLEMENT_STEMS - discovered_stems
    self.assertFalse(
      missing,
      f"Expected feature-implement OpenCode subagent stems missing from skills/ walk: {sorted(missing)}",
    )


if __name__ == "__main__":
  unittest.main()
