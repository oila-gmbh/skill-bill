"""Parametrized coverage for every shipped platform pack.

Supersedes the Kotlin-only canary. Loads each real platform pack through the
shell+content contract loader and asserts contract compliance and discovery
semantics.
"""

from __future__ import annotations

from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.shell_content_contract import (  # noqa: E402
  APPROVED_CODE_REVIEW_AREAS,
  SHELL_CONTRACT_VERSION,
  discover_platform_packs,
  load_platform_pack,
)


PLATFORM_PACKS_ROOT = ROOT / "platform-packs"
EXPECTED_SLUGS: frozenset[str] = frozenset(
  {"kmp", "kotlin"}
)


class PlatformPacksTest(unittest.TestCase):
  maxDiff = None

  def test_discovery_returns_every_shipped_pack(self) -> None:
    discovered = discover_platform_packs(PLATFORM_PACKS_ROOT)
    slugs = {pack.slug for pack in discovered}
    self.assertEqual(slugs, set(EXPECTED_SLUGS))

  def test_every_pack_matches_shell_contract_version(self) -> None:
    for pack in discover_platform_packs(PLATFORM_PACKS_ROOT):
      with self.subTest(pack=pack.slug):
        self.assertEqual(pack.contract_version, SHELL_CONTRACT_VERSION)

  def test_every_pack_declares_only_approved_areas(self) -> None:
    for pack in discover_platform_packs(PLATFORM_PACKS_ROOT):
      with self.subTest(pack=pack.slug):
        for area in pack.declared_code_review_areas:
          self.assertIn(area, APPROVED_CODE_REVIEW_AREAS)

  def test_every_declared_file_exists(self) -> None:
    for pack in discover_platform_packs(PLATFORM_PACKS_ROOT):
      with self.subTest(pack=pack.slug):
        self.assertTrue(
          pack.declared_files["baseline"].is_file(),
          f"{pack.slug}: baseline file missing at {pack.declared_files['baseline']}",
        )
        for area, path in pack.declared_files["areas"].items():
          self.assertTrue(
            path.is_file(),
            f"{pack.slug}: area file '{area}' missing at {path}",
          )

  def test_routed_skill_contract_preserved(self) -> None:
    for pack in discover_platform_packs(PLATFORM_PACKS_ROOT):
      with self.subTest(pack=pack.slug):
        self.assertEqual(pack.routed_skill_name, f"bill-{pack.slug}-code-review")

  def test_individual_pack_loaders(self) -> None:
    for slug in EXPECTED_SLUGS:
      with self.subTest(pack=slug):
        pack = load_platform_pack(PLATFORM_PACKS_ROOT / slug)
        self.assertEqual(pack.slug, slug)


if __name__ == "__main__":
  unittest.main()
