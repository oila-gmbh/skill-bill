from __future__ import annotations

import sys
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLATFORM_PACKS_DIR = ROOT / "platform-packs"

REQUIRED_FIELDS: tuple[str, ...] = ("name", "description", "developer_instructions")

CLAUDE_ONLY_FORBIDDEN: tuple[str, ...] = (
  "Agent(subagent_type=",
  "Task tool",
  "subagent_type=",
)


def discover_codex_agent_tomls() -> list[Path]:
  if not PLATFORM_PACKS_DIR.is_dir():
    return []
  return sorted(PLATFORM_PACKS_DIR.rglob("codex-agents/*.toml"))


class CodexAgentsTomlTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    if sys.version_info < (3, 11):
      self.skipTest("tomllib requires Python 3.11+")
    self.toml_paths = discover_codex_agent_tomls()
    self.assertGreater(
      len(self.toml_paths),
      0,
      "Expected at least one platform-packs/**/codex-agents/*.toml file",
    )

  def test_each_toml_is_parseable_with_required_fields(self) -> None:
    for toml_path in self.toml_paths:
      with self.subTest(toml=str(toml_path.relative_to(ROOT))):
        with toml_path.open("rb") as handle:
          parsed = tomllib.load(handle)
        for field in REQUIRED_FIELDS:
          self.assertIn(field, parsed, f"missing field '{field}' in {toml_path}")
          value = parsed[field]
          self.assertIsInstance(value, str, f"field '{field}' must be a string in {toml_path}")
          self.assertTrue(
            value.strip(),
            f"field '{field}' must be non-empty in {toml_path}",
          )

  def test_each_toml_name_matches_filename_stem(self) -> None:
    for toml_path in self.toml_paths:
      with self.subTest(toml=str(toml_path.relative_to(ROOT))):
        with toml_path.open("rb") as handle:
          parsed = tomllib.load(handle)
        self.assertEqual(
          parsed.get("name"),
          toml_path.stem,
          f"name field must match filename stem for {toml_path}",
        )

  def test_names_unique_across_all_codex_agent_tomls(self) -> None:
    seen: dict[str, Path] = {}
    for toml_path in self.toml_paths:
      with toml_path.open("rb") as handle:
        parsed = tomllib.load(handle)
      name = parsed.get("name", "")
      if name in seen:
        self.fail(
          f"Duplicate Codex agent name '{name}' in {toml_path} and {seen[name]}",
        )
      seen[name] = toml_path

  def test_developer_instructions_have_no_claude_only_references(self) -> None:
    for toml_path in self.toml_paths:
      with self.subTest(toml=str(toml_path.relative_to(ROOT))):
        with toml_path.open("rb") as handle:
          parsed = tomllib.load(handle)
        instructions = parsed.get("developer_instructions", "")
        for forbidden in CLAUDE_ONLY_FORBIDDEN:
          self.assertNotIn(
            forbidden,
            instructions,
            f"developer_instructions in {toml_path} must not contain Claude-only token '{forbidden}'",
          )

  def test_developer_instructions_inline_specialist_contract(self) -> None:
    required_markers = (
      "Shared Contract For Every Specialist",
      "Shared Report Structure",
      "[F-001]",
    )
    for toml_path in self.toml_paths:
      with self.subTest(toml=str(toml_path.relative_to(ROOT))):
        with toml_path.open("rb") as handle:
          parsed = tomllib.load(handle)
        instructions = parsed.get("developer_instructions", "")
        for marker in required_markers:
          self.assertIn(
            marker,
            instructions,
            f"developer_instructions in {toml_path} must inline F-XXX contract marker '{marker}'",
          )


if __name__ == "__main__":
  unittest.main()
