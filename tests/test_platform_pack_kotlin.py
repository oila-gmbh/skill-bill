"""Canary coverage for the relocated Kotlin platform pack.

Loads the real ``platform-packs/kotlin/`` pack through the shell+content
contract loader and asserts that (a) the contract version matches, (b)
declared areas align with the approved set, and (c) every declared file
exists with the required H2 sections.
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
  load_platform_pack,
)


KOTLIN_PACK_ROOT = ROOT / "platform-packs" / "kotlin"


class KotlinPlatformPackTest(unittest.TestCase):
  maxDiff = None

  def test_pack_loads_cleanly(self) -> None:
    pack = load_platform_pack(KOTLIN_PACK_ROOT)
    self.assertEqual(pack.slug, "kotlin")
    self.assertEqual(pack.contract_version, SHELL_CONTRACT_VERSION)
    self.assertEqual(pack.routed_skill_name, "bill-kotlin-code-review")

  def test_declared_areas_are_approved(self) -> None:
    pack = load_platform_pack(KOTLIN_PACK_ROOT)
    declared = set(pack.declared_code_review_areas)
    self.assertTrue(declared.issubset(APPROVED_CODE_REVIEW_AREAS))
    self.assertIn("architecture", declared)
    self.assertIn("platform-correctness", declared)

  def test_declared_files_exist(self) -> None:
    pack = load_platform_pack(KOTLIN_PACK_ROOT)
    baseline: Path = pack.declared_files["baseline"]
    self.assertTrue(baseline.is_file(), f"missing baseline at {baseline}")
    for area, path in pack.declared_files["areas"].items():
      self.assertTrue(path.is_file(), f"missing area file for '{area}' at {path}")


if __name__ == "__main__":
  unittest.main()
