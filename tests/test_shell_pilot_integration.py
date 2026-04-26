"""SKILL-14 integration coverage for shell-pilot-specific behavior.

This suite keeps only the checks that are unique to the shell pilot:

- the shell is platform-independent and references the contract sidecar
- stack-routing guidance stays discovery-driven
- documented shell contract versions remain in lockstep
"""

from __future__ import annotations

from pathlib import Path
import re
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.shell_content_contract import SHELL_CONTRACT_VERSION  # noqa: E402


PLATFORM_PACKS_ROOT = ROOT / "platform-packs"


class ShellPilotIntegrationTest(unittest.TestCase):
  maxDiff = None

  # --- Shell is platform-independent (AC 1, 2, 3) -----------------------

  def test_shell_declares_contract_and_sidecar(self) -> None:
    shell_path = ROOT / "skills" / "bill-code-review" / "SKILL.md"
    content_path = ROOT / "skills" / "bill-code-review" / "content.md"
    skill_text = shell_path.read_text(encoding="utf-8")
    content_text = content_path.read_text(encoding="utf-8")
    combined = f"{skill_text}\n{content_text}"
    self.assertIn("[shell-content-contract.md](shell-content-contract.md)", skill_text)
    self.assertIn(f"`{SHELL_CONTRACT_VERSION}`", combined)
    for slug in self._live_slugs():
      self.assertNotIn(
        f"signals dominate, delegate to `bill-{slug}-code-review`.",
        combined,
        f"shell still hardcodes routing for {slug}",
      )

  def test_shell_content_contract_sidecar_symlink_exists(self) -> None:
    sidecar = ROOT / "skills" / "bill-code-review" / "shell-content-contract.md"
    self.assertTrue(sidecar.exists())
    self.assertTrue(sidecar.is_symlink())

  # --- Stack-routing is discovery-driven (AC 9) --------------------------

  def test_stack_routing_playbook_is_discovery_driven(self) -> None:
    playbook_path = ROOT / "orchestration" / "stack-routing" / "PLAYBOOK.md"
    playbook = playbook_path.read_text(encoding="utf-8")
    self.assertIn("platform-packs/", playbook)
    self.assertIn("Discovery Algorithm", playbook)

    # AC 9 — no platform slug may appear as a section heading. The slug
    # list is derived from the live ``platform-packs/`` directory so this
    # test does not pin a count and catches any new slug added later.
    live_slugs = self._live_slugs()
    self.assertGreater(
      len(live_slugs),
      0,
      "expected at least one platform pack to derive the slug list from",
    )
    for slug in live_slugs:
      # Match any markdown heading (##, ###, ####, ...) whose line body
      # contains the slug as a standalone token. Word-boundary-style
      # matching prevents false positives like "Algorithm" matching the
      # short slugs, while still catching accidental "### Kotlin routing"
      # or "## kmp" headings.
      escaped = re.escape(slug)
      forbidden_heading = re.compile(
        rf"(?mi)^#{{2,6}}\s+.*(?<![A-Za-z0-9-]){escaped}(?![A-Za-z0-9-]).*$",
      )
      match = forbidden_heading.search(playbook)
      matched_line = match.group(0) if match else ""
      self.assertIsNone(
        match,
        f"stack-routing PLAYBOOK.md must not enumerate platform slug "
        f"'{slug}' as a section heading (found {matched_line!r} — "
        "move the detail into the owning pack's manifest).",
      )

  # --- SHELL_CONTRACT_VERSION drift guard (A-002) ------------------------

  def test_documented_shell_contract_versions_do_not_drift(self) -> None:
    """Every doc site that quotes the shell contract version must stay in
    lockstep with ``SHELL_CONTRACT_VERSION``.

    The check is a drift test, not auto-rewrite: each target file must
    contain a contextual mention of "shell contract version" followed by
    the exact current version string. When the shell bumps the version, any
    file that forgot to update produces a clear per-file failure message.
    """
    # (relative_path, contextual_regex) — the regex captures the version
    # string next to enough context that we know it really refers to the
    # shell contract version (and not, e.g., an unrelated "1.0" elsewhere).
    documented_sites: tuple[tuple[str, re.Pattern[str]], ...] = (
      (
        "AGENTS.md",
        re.compile(r"current shell contract version is (\d+\.\d+)"),
      ),
      (
        "docs/getting-started-for-teams.md",
        re.compile(r"contract_version:\s*\"([^\"]+)\""),
      ),
      (
        "orchestration/shell-content-contract/PLAYBOOK.md",
        re.compile(r"current shell contract version is \*\*`([^`]+)`\*\*"),
      ),
      (
        "skills/bill-code-review/content.md",
        re.compile(r"shell contract version `([^`]+)`"),
      ),
    )

    for relative_path, pattern in documented_sites:
      with self.subTest(path=relative_path):
        path = ROOT / relative_path
        self.assertTrue(
          path.is_file(),
          f"expected documented contract-version site '{relative_path}' to exist",
        )
        text = path.read_text(encoding="utf-8")
        match = pattern.search(text)
        self.assertIsNotNone(
          match,
          f"{relative_path}: could not locate shell contract version mention "
          f"using pattern {pattern.pattern!r}",
        )
        matched_version = match.group(1)
        self.assertEqual(
          matched_version,
          SHELL_CONTRACT_VERSION,
          f"{relative_path}: documented shell contract version "
          f"'{matched_version}' drifted from SHELL_CONTRACT_VERSION "
          f"'{SHELL_CONTRACT_VERSION}'. Update the doc to match the shell.",
        )

  @staticmethod
  def _live_slugs() -> list[str]:
    return sorted(
      entry.name
      for entry in PLATFORM_PACKS_ROOT.iterdir()
      if entry.is_dir() and not entry.name.startswith(".")
    )


if __name__ == "__main__":
  unittest.main()
