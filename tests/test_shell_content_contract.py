"""Fixture-based accept/reject coverage for the shell+content contract loader.

Mirrors the fixture pattern used by ``test_validate_agent_configs_e2e.py`` so
acceptance and rejection paths are first-class. Every rejection asserts the
specific named exception and that the offending artifact is referenced in the
error message.
"""

from __future__ import annotations

from pathlib import Path
import sys
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill import shell_content_contract  # noqa: E402
from skill_bill.shell_content_contract import (  # noqa: E402
  ContractVersionMismatchError,
  InvalidManifestSchemaError,
  MissingContentFileError,
  MissingManifestError,
  MissingRequiredSectionError,
  PlatformPack,
  PyYAMLMissingError,
  SHELL_CONTRACT_VERSION,
  load_platform_pack,
)


FIXTURES_ROOT = ROOT / "tests" / "fixtures" / "shell_content_contract"


class ShellContentContractLoaderTest(unittest.TestCase):
  maxDiff = None

  def test_loads_valid_pack(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "valid_pack")
    self.assertIsInstance(pack, PlatformPack)
    self.assertEqual(pack.slug, "valid_pack")
    self.assertEqual(pack.contract_version, SHELL_CONTRACT_VERSION)
    self.assertEqual(pack.declared_code_review_areas, ("architecture",))
    self.assertEqual(pack.routing_signals.strong, (".fixture",))
    self.assertEqual(pack.routed_skill_name, "bill-valid_pack-code-review")

  def test_rejects_missing_manifest(self) -> None:
    with self.assertRaises(MissingManifestError) as context:
      load_platform_pack(FIXTURES_ROOT / "missing_manifest")
    self.assertIn("missing_manifest", str(context.exception))
    self.assertIn("platform.yaml", str(context.exception))

  def test_rejects_missing_content_file(self) -> None:
    with self.assertRaises(MissingContentFileError) as context:
      load_platform_pack(FIXTURES_ROOT / "missing_content_file")
    message = str(context.exception)
    self.assertIn("missing_content_file", message)
    self.assertIn("baseline", message)
    self.assertIn("code-review/SKILL.md", message)

  def test_rejects_bad_version(self) -> None:
    with self.assertRaises(ContractVersionMismatchError) as context:
      load_platform_pack(FIXTURES_ROOT / "bad_version")
    message = str(context.exception)
    self.assertIn("bad_version", message)
    self.assertIn("9.99", message)
    self.assertIn(SHELL_CONTRACT_VERSION, message)

  def test_rejects_missing_section(self) -> None:
    with self.assertRaises(MissingRequiredSectionError) as context:
      load_platform_pack(FIXTURES_ROOT / "missing_section")
    message = str(context.exception)
    self.assertIn("missing_section", message)
    self.assertIn("## Telemetry Ceremony Hooks", message)

  def test_rejects_invalid_schema(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError) as context:
      load_platform_pack(FIXTURES_ROOT / "invalid_schema")
    message = str(context.exception)
    self.assertIn("invalid_schema", message)
    self.assertIn("routing_signals", message)

  # --- Additional InvalidManifestSchemaError coverage (T-005) ------------

  def test_rejects_declared_code_review_areas_not_a_list(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError) as context:
      load_platform_pack(FIXTURES_ROOT / "schema_areas_wrong_type")
    message = str(context.exception)
    self.assertIn("schema_areas_wrong_type", message)
    self.assertIn("declared_code_review_areas", message)

  def test_rejects_unapproved_area_in_declared_code_review_areas(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError) as context:
      load_platform_pack(FIXTURES_ROOT / "schema_unapproved_area")
    message = str(context.exception)
    self.assertIn("schema_unapproved_area", message)
    self.assertIn("laravel", message)
    self.assertIn("declared area", message)

  def test_rejects_non_boolean_governs_addons(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError) as context:
      load_platform_pack(FIXTURES_ROOT / "schema_governs_addons_wrong_type")
    message = str(context.exception)
    self.assertIn("schema_governs_addons_wrong_type", message)
    self.assertIn("governs_addons", message)

  # --- Additional contract-error coverage (A-003, P-001) -----------------

  def test_rejects_extra_area_in_declared_files(self) -> None:
    with self.assertRaises(InvalidManifestSchemaError) as context:
      load_platform_pack(FIXTURES_ROOT / "extra_area")
    message = str(context.exception)
    self.assertIn("extra_area", message)
    self.assertIn("declared_files.areas", message)
    self.assertIn("performance", message)

  def test_rejects_required_section_only_inside_fenced_code_block(self) -> None:
    with self.assertRaises(MissingRequiredSectionError) as context:
      load_platform_pack(FIXTURES_ROOT / "heading_in_fence")
    message = str(context.exception)
    self.assertIn("heading_in_fence", message)
    self.assertIn("## Specialist Scope", message)

  # --- PyYAML missing coverage (P-002) -----------------------------------

  def test_raises_pyyaml_missing_error_when_yaml_import_fails(self) -> None:
    with mock.patch.object(
      shell_content_contract,
      "_import_yaml",
      side_effect=PyYAMLMissingError(
        "PyYAML is required to load platform packs. Install it via the "
        "project venv (`./.venv/bin/pip install pyyaml>=6`) or run the "
        "validator through `.venv/bin/python3 scripts/validate_agent_configs.py`."
      ),
    ):
      with self.assertRaises(PyYAMLMissingError) as context:
        load_platform_pack(FIXTURES_ROOT / "valid_pack")
    message = str(context.exception)
    self.assertIn("PyYAML", message)
    self.assertIn(".venv/bin/pip install pyyaml", message)


if __name__ == "__main__":
  unittest.main()
