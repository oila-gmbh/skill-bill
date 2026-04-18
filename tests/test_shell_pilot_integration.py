"""SKILL-14 integration coverage.

End-to-end contract checks for the shell + content pilot. Exercises every
major acceptance-criterion claim with one assertion per path:

- the shell loads all six shipped packs and discovery returns exactly those
  slugs
- every loud-fail rejection surfaces the specific named exception
- the routed-skill contract is preserved
- the shell is platform-independent and references the contract sidecar
- horizontal skills listed in AC 14 remain authored and unmodified in
  location (a presence check, not a content-equivalence check — their
  content evolves independently)
"""

from __future__ import annotations

from pathlib import Path
import re
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.shell_content_contract import (  # noqa: E402
  ContractVersionMismatchError,
  InvalidManifestSchemaError,
  MissingContentFileError,
  MissingManifestError,
  MissingRequiredSectionError,
  SHELL_CONTRACT_VERSION,
  discover_platform_packs,
  load_platform_pack,
)


PLATFORM_PACKS_ROOT = ROOT / "platform-packs"
FIXTURES_ROOT = ROOT / "tests" / "fixtures" / "shell_content_contract"
EXPECTED_SLUGS: frozenset[str] = frozenset(
  {"agent-config", "backend-kotlin", "go", "kmp", "kotlin", "php"}
)

HORIZONTAL_SKILLS: tuple[str, ...] = (
  "bill-grill-plan",
  "bill-boundary-decisions",
  "bill-boundary-history",
  "bill-pr-description",
  "bill-skill-scaffold",
  "bill-feature-guard",
  "bill-feature-guard-cleanup",
  "bill-unit-test-value-check",
)


class ShellPilotIntegrationTest(unittest.TestCase):
  maxDiff = None

  # --- Shell loads every shipped pack (AC 7, 8) --------------------------

  def test_shell_loads_every_shipped_pack(self) -> None:
    packs = discover_platform_packs(PLATFORM_PACKS_ROOT)
    slugs = {pack.slug for pack in packs}
    self.assertEqual(slugs, set(EXPECTED_SLUGS))

  def test_discovery_returns_exactly_six_slugs(self) -> None:
    # AC 9 — manifest-driven discovery, no hardcoded enumeration.
    packs = discover_platform_packs(PLATFORM_PACKS_ROOT)
    self.assertEqual(len(packs), 6)

  def test_routed_skill_contract_preserved(self) -> None:
    # AC 13 — existing references to platform skill names must still work.
    for pack in discover_platform_packs(PLATFORM_PACKS_ROOT):
      with self.subTest(pack=pack.slug):
        self.assertEqual(pack.routed_skill_name, f"bill-{pack.slug}-code-review")

  # --- Loud-fail rejections name the offending artifact (AC 4, 12) -------

  def test_missing_manifest_raises_specific_error(self) -> None:
    with self.assertRaises(MissingManifestError):
      load_platform_pack(FIXTURES_ROOT / "missing_manifest")

  def test_missing_content_file_raises_specific_error(self) -> None:
    with self.assertRaises(MissingContentFileError):
      load_platform_pack(FIXTURES_ROOT / "missing_content_file")

  def test_bad_version_raises_specific_error(self) -> None:
    with self.assertRaises(ContractVersionMismatchError):
      load_platform_pack(FIXTURES_ROOT / "bad_version")

  def test_missing_section_raises_specific_error(self) -> None:
    with self.assertRaises(MissingRequiredSectionError):
      load_platform_pack(FIXTURES_ROOT / "missing_section")

  def test_invalid_schema_raises_specific_error(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError):
      load_platform_pack(FIXTURES_ROOT / "invalid_schema")

  # --- Shell is platform-independent (AC 1, 2, 3) -----------------------

  def test_shell_declares_contract_and_sidecar(self) -> None:
    shell_path = ROOT / "skills" / "base" / "bill-code-review" / "SKILL.md"
    text = shell_path.read_text(encoding="utf-8")
    # The shell references the shell+content contract sidecar.
    self.assertIn("[shell-content-contract.md](shell-content-contract.md)", text)
    # The shell declares the current contract version.
    self.assertIn(f"`{SHELL_CONTRACT_VERSION}`", text)
    # The shell no longer hardcodes per-platform delegation lines.
    for slug in EXPECTED_SLUGS:
      self.assertNotIn(
        f"signals dominate, delegate to `bill-{slug}-code-review`.",
        text,
        f"shell still hardcodes routing for {slug}",
      )

  def test_shell_content_contract_sidecar_symlink_exists(self) -> None:
    sidecar = ROOT / "skills" / "base" / "bill-code-review" / "shell-content-contract.md"
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
    live_slugs = sorted(
      entry.name
      for entry in PLATFORM_PACKS_ROOT.iterdir()
      if entry.is_dir() and not entry.name.startswith(".")
    )
    self.assertGreater(
      len(live_slugs),
      0,
      "expected at least one platform pack to derive the slug list from",
    )
    for slug in live_slugs:
      # Match any markdown heading (##, ###, ####, ...) whose line body
      # contains the slug as a standalone token. Word-boundary-style
      # matching prevents false positives like "Algorithm" matching the
      # "go" slug, while still catching accidental "### Kotlin routing"
      # or "## backend-kotlin" headings.
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
        "skills/base/bill-code-review/SKILL.md",
        re.compile(r"shell contract version \*\*`([^`]+)`\*\*"),
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

  # --- Horizontal skills untouched (AC 14) -------------------------------

  def test_horizontal_skills_remain_unmodified_in_skills_base(self) -> None:
    for skill in HORIZONTAL_SKILLS:
      with self.subTest(skill=skill):
        skill_path = ROOT / "skills" / "base" / skill / "SKILL.md"
        self.assertTrue(skill_path.is_file(), f"{skill} went missing")


if __name__ == "__main__":
  unittest.main()
