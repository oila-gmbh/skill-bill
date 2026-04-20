"""Regression guard for the content.md ceremony-taxonomy split (SKILL-21 pass 2).

Every governed ``content.md`` under ``platform-packs/`` must carry only
author-owned skill knowledge (signals, rubrics, routing tables,
project-specific rules). Shell-owned ceremony (output contracts,
session/run IDs, severity/confidence scales, risk-register format,
telemetry pointers, sidecar pointers, learnings resolution,
execution-mode descriptions, scope-determination bullet lists, overview
duplicates) must live in SKILL.md or the shell runtime, never in
``content.md``.

This test walks every shipped ``content.md`` and fails if any of the
blacklisted H2 headings reappears. CI uses it as a regression guard: if
a contributor re-introduces ceremony into content.md, this test fires.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.shell_content_contract import (  # noqa: E402
  CEREMONY_FREE_FORM_H2S,
)


PLATFORM_PACKS_ROOT = ROOT / "platform-packs"

_SECTION_HEADING_PATTERN = re.compile(r"^##\s+[^\n]+$")
_FENCE_PATTERN = re.compile(r"^\s*(?:```|~~~)")


def _collect_top_level_h2_headings(text: str) -> set[str]:
  headings: set[str] = set()
  in_fence = False
  for line in text.splitlines():
    if _FENCE_PATTERN.match(line):
      in_fence = not in_fence
      continue
    if in_fence:
      continue
    if _SECTION_HEADING_PATTERN.match(line):
      headings.add(line.strip())
  return headings


class ContentMdHygieneTest(unittest.TestCase):
  maxDiff = None

  def test_no_blacklisted_ceremony_heading_in_content_md(self) -> None:
    content_files = sorted(PLATFORM_PACKS_ROOT.rglob("content.md"))
    self.assertGreater(
      len(content_files),
      0,
      "expected at least one content.md under platform-packs/",
    )
    for content_file in content_files:
      with self.subTest(content=str(content_file.relative_to(ROOT))):
        text = content_file.read_text(encoding="utf-8")
        headings = _collect_top_level_h2_headings(text)
        leaked = sorted(headings & set(CEREMONY_FREE_FORM_H2S))
        self.assertEqual(
          leaked,
          [],
          f"{content_file.relative_to(ROOT)}: "
          f"blacklisted ceremony heading(s) {leaked} leaked into content.md. "
          "Move the section into SKILL.md (shell ceremony) or delete it.",
        )


if __name__ == "__main__":
  unittest.main()
