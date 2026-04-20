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
  CANONICAL_EXECUTION_BODY,
  ContractVersionMismatchError,
  InvalidExecutionSectionError,
  InvalidManifestSchemaError,
  MissingContentBodyFileError,
  MissingContentFileError,
  MissingManifestError,
  MissingRequiredSectionError,
  PlatformPack,
  PyYAMLMissingError,
  SHELL_CONTRACT_VERSION,
  assert_execution_body_matches,
  detect_template_drift,
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


class QualityCheckContentContractTest(unittest.TestCase):
  """SKILL-16: optional declared_quality_check_file loader coverage."""

  maxDiff = None

  def test_loads_quality_check_only_fixture(self) -> None:
    pack = load_platform_pack(FIXTURES_ROOT / "quality_check_only")
    self.assertIsNotNone(pack.declared_quality_check_file)
    resolved = load_quality_check_content(pack)
    self.assertEqual(resolved, pack.declared_quality_check_file)
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
    self.assertIn("## Fix Strategy", message)

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


class SkillVersion11RulesTest(unittest.TestCase):
  """SKILL-21 AC 15(b): new loud-fail cases and drift detection."""

  maxDiff = None

  def _seed_minimal_valid_pack(self, root: Path) -> Path:
    pack_root = root / "pack"
    code_review_dir = pack_root / "code-review"
    code_review_dir.mkdir(parents=True)
    skill_file = code_review_dir / "SKILL.md"
    skill_file.write_text(
      "---\n"
      "name: pack-code-review\n"
      "description: Fixture.\n"
      "shell_contract_version: 1.1\n"
      "template_version: 2026.04.19\n"
      "---\n"
      "\n"
      "## Description\nFixture.\n\n"
      "## Specialist Scope\nFixture.\n\n"
      "## Inputs\nFixture.\n\n"
      "## Outputs Contract\nFixture.\n\n"
      "## Execution\n\nFollow the instructions in [content.md](content.md).\n\n"
      "## Execution Mode Reporting\nFixture.\n\n"
      "## Telemetry Ceremony Hooks\nFixture.\n",
      encoding="utf-8",
    )
    (code_review_dir / "content.md").write_text("# pack body\n", encoding="utf-8")
    (pack_root / "platform.yaml").write_text(
      "platform: pack\n"
      "contract_version: \"1.1\"\n"
      "display_name: Pack\n"
      "routing_signals:\n"
      "  strong:\n"
      "    - .fixture\n"
      "  tie_breakers: []\n"
      "  addon_signals: []\n"
      "declared_code_review_areas: []\n"
      "declared_files:\n"
      "  baseline: code-review/SKILL.md\n"
      "  areas: {}\n",
      encoding="utf-8",
    )
    return pack_root

  def test_contract_version_mismatch_on_v1_0_pack_points_at_migration_script(self) -> None:
    with mock.patch.object(shell_content_contract, "SHELL_CONTRACT_VERSION", "1.1"):
      with self.assertRaises(ContractVersionMismatchError) as ctx:
        load_platform_pack(FIXTURES_ROOT / "bad_version")
    self.assertIn("scripts/migrate_to_content_md.py", str(ctx.exception))

  def test_missing_content_md_sibling_raises_named_error(self) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      # Delete content.md to simulate the missing-sibling failure mode.
      (pack_root / "code-review" / "content.md").unlink()
      with self.assertRaises(MissingContentBodyFileError) as ctx:
        load_platform_pack(pack_root)
    self.assertIn("content.md", str(ctx.exception))

  def test_invalid_execution_body_raises_named_error(self) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      skill_file = pack_root / "code-review" / "SKILL.md"
      text = skill_file.read_text(encoding="utf-8")
      text = text.replace(
        "Follow the instructions in [content.md](content.md).",
        "Follow the instructions somewhere else.",
      )
      skill_file.write_text(text, encoding="utf-8")
      with self.assertRaises(InvalidExecutionSectionError):
        load_platform_pack(pack_root)

  def test_failure_precedence_contract_version_beats_content(self) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      # Delete content.md AND also downgrade the contract version. The
      # contract-version error must fire first per the documented precedence.
      (pack_root / "code-review" / "content.md").unlink()
      manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
      manifest = manifest.replace('contract_version: "1.1"', 'contract_version: "1.0"')
      (pack_root / "platform.yaml").write_text(manifest, encoding="utf-8")
      with self.assertRaises(ContractVersionMismatchError):
        load_platform_pack(pack_root)

  def test_failure_precedence_contract_version_beats_manifest_schema(self) -> None:
    """F-002: v1.0 packs surface the migration hint even with schema errors.

    A v1.0 pack combined with an unrelated schema error (e.g. an unapproved
    code-review area) must raise ``ContractVersionMismatchError`` — not
    ``InvalidManifestSchemaError`` — so the operator sees the migration
    script guidance documented in AC 1.
    """
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
      manifest = manifest.replace('contract_version: "1.1"', 'contract_version: "1.0"')
      manifest = manifest.replace(
        "declared_code_review_areas: []\n",
        "declared_code_review_areas:\n  - not-an-approved-area\n",
      )
      (pack_root / "platform.yaml").write_text(manifest, encoding="utf-8")
      with self.assertRaises(ContractVersionMismatchError) as ctx:
        load_platform_pack(pack_root)
    self.assertIn("scripts/migrate_to_content_md.py", str(ctx.exception))

  def test_template_version_drift_detection(self) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      skill_file = pack_root / "code-review" / "SKILL.md"
      self.assertFalse(
        detect_template_drift(skill_file, current_template_version="2026.04.19"),
      )
      self.assertTrue(
        detect_template_drift(skill_file, current_template_version="2099.01.01"),
      )

  def test_canonical_execution_body_matches_helper(self) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
      pack_root = self._seed_minimal_valid_pack(Path(tmp))
      skill_file = pack_root / "code-review" / "SKILL.md"
      assert_execution_body_matches(skill_file, context_label="test")
      # Confirm the canonical body is not empty and points at content.md.
      self.assertIn("[content.md](content.md)", CANONICAL_EXECUTION_BODY)


if __name__ == "__main__":
  unittest.main()
