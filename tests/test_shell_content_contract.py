"""Fixture-based accept/reject coverage for the shell+content contract loader.

Mirrors the fixture pattern used by ``test_validate_agent_configs_e2e.py`` so
acceptance and rejection paths are first-class. Every rejection asserts the
specific named exception and that the offending artifact is referenced in the
error message.
"""

from __future__ import annotations

from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill import shell_content_contract  # noqa: E402
from skill_bill.shell_content_contract import (  # noqa: E402
  ContractVersionMismatchError,
  InvalidDescriptorSectionError,
  InvalidManifestSchemaError,
  MissingContentFileError,
  MissingManifestError,
  MissingRequiredSectionError,
  MissingShellCeremonyFileError,
  PlatformPack,
  PyYAMLMissingError,
  SHELL_CONTRACT_VERSION,
  load_platform_pack,
  load_quality_check_content,
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
    self.assertIn("## Ceremony", message)

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

  def test_rejects_missing_area_metadata(self) -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
      fixture_root = Path(tmpdir) / "valid_pack"
      shutil.copytree(FIXTURES_ROOT / "valid_pack", fixture_root, symlinks=True)
      manifest_path = fixture_root / "platform.yaml"
      manifest_text = manifest_path.read_text(encoding="utf-8")
      manifest_text = re.sub(
        r"(?ms)^area_metadata:\n(?:  [^\n]+\n|    [^\n]+\n)*",
        "",
        manifest_text,
      )
      manifest_path.write_text(manifest_text, encoding="utf-8")

      with self.assertRaises(InvalidManifestSchemaError) as context:
        load_platform_pack(fixture_root)

    message = str(context.exception)
    self.assertIn("valid_pack", message)
    self.assertIn("area_metadata", message)

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
    self.assertIn("## Descriptor", message)

  def test_rejects_missing_shell_ceremony_sidecar(self) -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
      fixture_root = Path(tmpdir) / "valid_pack"
      shutil.copytree(FIXTURES_ROOT / "valid_pack", fixture_root, symlinks=True)
      (fixture_root / "code-review" / "shell-ceremony.md").unlink()

      with self.assertRaises(MissingShellCeremonyFileError) as context:
        load_platform_pack(fixture_root)

    message = str(context.exception)
    self.assertIn("valid_pack", message)
    self.assertIn("shell-ceremony.md", message)

  def test_rejects_descriptor_drift(self) -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
      fixture_root = Path(tmpdir) / "valid_pack"
      shutil.copytree(FIXTURES_ROOT / "valid_pack", fixture_root, symlinks=True)
      skill_path = fixture_root / "code-review" / "SKILL.md"
      skill_text = skill_path.read_text(encoding="utf-8").replace(
        "Governed skill: `code-review`",
        "Governed skill: `code-review-drifted`",
      )
      skill_path.write_text(skill_text, encoding="utf-8")

      with self.assertRaises(InvalidDescriptorSectionError) as context:
        load_platform_pack(fixture_root)

    message = str(context.exception)
    self.assertIn("valid_pack", message)
    self.assertIn("## Descriptor", message)

  def test_missing_shell_ceremony_fails_before_descriptor_drift(self) -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
      fixture_root = Path(tmpdir) / "valid_pack"
      shutil.copytree(FIXTURES_ROOT / "valid_pack", fixture_root, symlinks=True)
      skill_path = fixture_root / "code-review" / "SKILL.md"
      skill_text = skill_path.read_text(encoding="utf-8").replace(
        "Governed skill: `code-review`",
        "Governed skill: `code-review-drifted`",
      )
      skill_path.write_text(skill_text, encoding="utf-8")
      (fixture_root / "code-review" / "shell-ceremony.md").unlink()

      with self.assertRaises(MissingShellCeremonyFileError):
        load_platform_pack(fixture_root)

  # --- PyYAML missing coverage (P-002) -----------------------------------

  def test_raises_pyyaml_missing_error_when_yaml_import_fails(self) -> None:
    with mock.patch.object(
      shell_content_contract,
      "_import_yaml",
      side_effect=PyYAMLMissingError(
        "PyYAML is required to load platform packs. Install it via the "
        "project venv (`./.venv/bin/pip install pyyaml>=6`) or run the "
        "validator through `scripts/validate_agent_configs`."
      ),
    ):
      with self.assertRaises(PyYAMLMissingError) as context:
        load_platform_pack(FIXTURES_ROOT / "valid_pack")
    message = str(context.exception)
    self.assertIn("PyYAML", message)
    self.assertIn(".venv/bin/pip install pyyaml", message)


class QualityCheckContentContractTest(unittest.TestCase):
  """SKILL-16: optional declared_quality_check_file loader coverage."""

  maxDiff = None

  def test_loads_quality_check_only_fixture(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "quality_check_only")
    self.assertIsNotNone(pack.declared_quality_check_file)
    resolved = load_quality_check_content(pack)
    self.assertEqual(resolved, pack.declared_quality_check_file.with_name("content.md"))
    self.assertTrue(resolved.is_file())

  def test_loads_code_review_and_quality_check_fixture(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "code_review_and_quality_check")
    self.assertIsNotNone(pack.declared_quality_check_file)
    resolved = load_quality_check_content(pack)
    self.assertTrue(resolved.is_file())
    # Both code-review baseline and quality-check files must succeed.
    self.assertEqual(pack.declared_code_review_areas, ("architecture",))

  def test_rejects_quality_check_missing_file(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "quality_check_missing_file")
    with self.assertRaises(MissingContentFileError) as context:
      load_quality_check_content(pack)
    message = str(context.exception)
    self.assertIn("quality_check_missing_file", message)
    self.assertIn("does-not-exist.md", message)

  def test_rejects_quality_check_missing_section(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "quality_check_missing_section")
    with self.assertRaises(MissingRequiredSectionError) as context:
      load_quality_check_content(pack)
    message = str(context.exception)
    self.assertIn("quality_check_missing_section", message)
    self.assertIn("## Ceremony", message)

  def test_valid_pack_without_quality_check_key_is_none(self) -> None:
    """A pack that does NOT declare the key has declared_quality_check_file=None.

    Calling load_quality_check_content on such a pack raises
    MissingContentFileError rather than silently returning nothing.
    """
    pack = load_platform_pack(FIXTURES_ROOT / "valid_pack")
    self.assertIsNone(pack.declared_quality_check_file)
    with self.assertRaises(MissingContentFileError) as context:
      load_quality_check_content(pack)
    self.assertIn("valid_pack", str(context.exception))


if __name__ == "__main__":
  unittest.main()
