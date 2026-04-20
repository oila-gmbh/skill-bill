"""Fixture-based acceptance and rejection coverage for the new-skill scaffolder.

Mirrors :mod:`tests.test_shell_content_contract` in shape: each test runs
against a ``tmp_path`` scratch repo seeded from
``tests/fixtures/scaffold/seed_repo``. We never mutate the real repository,
never write to ``$HOME``, and always monkeypatch the validator and the
auto-install so a local test run is hermetic.

Covered cases (SKILL-15 AC16 + SKILL-19 follow-on):

- Happy paths: horizontal, first-class platform-pack creation, code-review
  area, add-on flat, pre-shell family override with interim-location note.
- Invalid payload → :class:`InvalidScaffoldPayloadError`.
- Wrong ``scaffold_payload_version`` → :class:`ScaffoldPayloadVersionMismatchError`.
- Rollback on validator failure — tree byte-identical to pre-run.
- Rollback on manifest-write failure.
- Rollback on symlink-creation failure.
- Idempotency: second run fails with :class:`SkillAlreadyExistsError`.
- Agent detection with zero / one / all five agent dirs.
- Scaffolder-owned sections byte-identical across specialists in a family.
"""

from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill import install as install_module  # noqa: E402
from skill_bill import scaffold as scaffold_module  # noqa: E402
from skill_bill import scaffold_manifest as scaffold_manifest_module  # noqa: E402
from skill_bill.install import AgentTarget  # noqa: E402
from skill_bill.scaffold import scaffold  # noqa: E402
from skill_bill.scaffold_exceptions import (  # noqa: E402
  InvalidScaffoldPayloadError,
  MissingSupportingFileTargetError,
  ScaffoldPayloadVersionMismatchError,
  ScaffoldRollbackError,
  ScaffoldValidatorError,
  SkillAlreadyExistsError,
)
from skill_bill.scaffold_template import extract_scaffolder_owned  # noqa: E402


FIXTURES_ROOT = ROOT / "tests" / "fixtures" / "scaffold"


def _load_validate_skill_file():
  """Import ``validate_skill_file`` with ``scripts/`` on ``sys.path``.

  ``scripts/validate_agent_configs.py`` does a bare ``from
  skill_repo_contracts import ...`` that requires ``scripts/`` to be on
  ``sys.path``. Tests import the validator directly so the sys.path
  mutation is scoped here rather than polluting every test module.
  """
  scripts_dir = ROOT / "scripts"
  if str(scripts_dir) not in sys.path:
    sys.path.insert(0, str(scripts_dir))
  from scripts.validate_agent_configs import validate_skill_file  # noqa: WPS433
  return validate_skill_file


_KOTLIN_MANIFEST = """\
platform: kotlin
contract_version: "1.0"
display_name: Kotlin
governs_addons: false

routing_signals:
  strong:
    - ".kt"
  tie_breakers:
    - "fallback tie-breaker"
  addon_signals: []

declared_code_review_areas:
  - architecture

declared_files:
  baseline: code-review/bill-kotlin-code-review/SKILL.md
  areas:
    architecture: code-review/bill-kotlin-code-review-architecture/SKILL.md
"""

_KMP_MANIFEST = """\
platform: kmp
contract_version: "1.0"
display_name: KMP
governs_addons: true

routing_signals:
  strong:
    - "androidMain"
  tie_breakers:
    - "prefer KMP for multiplatform fixtures"
  addon_signals:
    - "android-compose"

declared_code_review_areas:
  - ui

declared_files:
  baseline: code-review/bill-kmp-code-review/SKILL.md
  areas:
    ui: code-review/bill-kmp-code-review-ui/SKILL.md
"""


def _seed_skill_file(path: Path) -> None:
  """Write a minimal six-section SKILL.md at ``path``."""
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(
    "---\n"
    f"name: {path.parent.name}\n"
    "description: Fixture content.\n"
    "---\n\n"
    "## Description\n.\n\n"
    "## Specialist Scope\n.\n\n"
    "## Inputs\n.\n\n"
    "## Outputs Contract\n.\n\n"
    "## Execution Mode Reporting\n.\n\n"
    "## Telemetry Ceremony Hooks\n.\n",
    encoding="utf-8",
  )


def _build_seed_repo(tmp_path: Path) -> Path:
  """Seed a minimal scratch repo layout that the scaffolder can operate on.

  We intentionally avoid copying the real repo — tests must not depend on
  the main tree for correctness.
  """
  repo = tmp_path / "repo"
  (repo / "skills").mkdir(parents=True)
  # Seed a minimal base capability directory so the repo-level validator
  # (``validate_platform_skill_name``) can resolve pre-shell platform
  # overrides like ``bill-php-feature-verify`` without tripping on missing
  # base capabilities.
  (repo / "skills" / "bill-feature-verify").mkdir(parents=True)
  (repo / "skills" / "php").mkdir(parents=True)
  kotlin_pack_root = repo / "platform-packs" / "kotlin"
  kotlin_pack_root.mkdir(parents=True)
  (kotlin_pack_root / "platform.yaml").write_text(_KOTLIN_MANIFEST, encoding="utf-8")
  _seed_skill_file(kotlin_pack_root / "code-review" / "bill-kotlin-code-review" / "SKILL.md")
  _seed_skill_file(
    kotlin_pack_root / "code-review" / "bill-kotlin-code-review-architecture" / "SKILL.md"
  )
  kmp_pack_root = repo / "platform-packs" / "kmp"
  kmp_pack_root.mkdir(parents=True)
  (kmp_pack_root / "platform.yaml").write_text(_KMP_MANIFEST, encoding="utf-8")
  _seed_skill_file(kmp_pack_root / "code-review" / "bill-kmp-code-review" / "SKILL.md")
  _seed_skill_file(
    kmp_pack_root / "code-review" / "bill-kmp-code-review-ui" / "SKILL.md"
  )
  # No scripts/validate_agent_configs.py in the scratch repo; the scaffolder
  # skips the validator in that case. Tests that want to exercise validator
  # failure monkeypatch ``_run_validator`` explicitly.
  return repo


def _snapshot_tree(root: Path) -> dict[str, bytes]:
  """Return a deterministic snapshot of every regular file under ``root``."""
  snapshot: dict[str, bytes] = {}
  for path in sorted(root.rglob("*")):
    if path.is_file() and not path.is_symlink():
      snapshot[str(path.relative_to(root))] = path.read_bytes()
  return snapshot


class _NoAgentsPatch:
  """Context manager that forces :func:`detect_agents` to return an empty list."""

  def __enter__(self) -> "_NoAgentsPatch":
    self._patcher = mock.patch.object(install_module, "detect_agents", return_value=[])
    self._patcher.start()
    return self

  def __exit__(self, *exc_info: object) -> None:
    self._patcher.stop()


class ScaffoldHappyPathsTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def _payload(self, **overrides: object) -> dict:
    payload: dict[str, object] = {
      "scaffold_payload_version": "1.0",
      "repo_root": str(self.repo),
    }
    payload.update(overrides)
    return payload

  def test_horizontal(self) -> None:
    result = scaffold(self._payload(kind="horizontal", name="bill-horizontal-new"))
    self.assertEqual(result.kind, "horizontal")
    skill_md = self.repo / "skills" / "bill-horizontal-new" / "SKILL.md"
    self.assertTrue(skill_md.is_file())
    body = skill_md.read_text(encoding="utf-8")
    self.assertIn("## Description", body)
    self.assertIn("## Execution Mode Reporting", body)
    # F-001: horizontal skills are validated by validate_skill_file, which
    # requires the Project Overrides heading and a literal reference to
    # .agents/skill-overrides.md.
    self.assertIn("## Project Overrides", body)
    self.assertIn(".agents/skill-overrides.md", body)

  def test_code_review_area(self) -> None:
    result = scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-kotlin-code-review-performance",
        platform="kotlin",
        area="performance",
      )
    )
    self.assertEqual(result.kind, "code-review-area")
    skill_md = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-performance"
      / "SKILL.md"
    )
    self.assertTrue(skill_md.is_file())
    manifest = (self.repo / "platform-packs" / "kotlin" / "platform.yaml").read_text(
      encoding="utf-8"
    )
    self.assertIn("performance", manifest)
    self.assertIn(
      "performance: code-review/bill-kotlin-code-review-performance/SKILL.md",
      manifest,
    )
    # SKILL-21 follow-up: platform-pack SKILL.md files now render
    # ``## Project Overrides`` as shell governance (previously the section
    # leaked into ``content.md`` via the migration). It stays next to the
    # shell so overrides precedence lives in SKILL.md, not in the
    # author-owned content body.
    body = skill_md.read_text(encoding="utf-8")
    self.assertIn("## Project Overrides", body)
    self.assertIn(".agents/skill-overrides.md", body)

  def test_platform_pack(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        display_name="Java",
        description="Use when reviewing Java server and library changes.",
      )
    )
    self.assertEqual(result.kind, "platform-pack")

    pack_root = self.repo / "platform-packs" / "java"
    manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
    self.assertIn('platform: "java"', manifest)
    self.assertIn('display_name: "Java"', manifest)
    self.assertIn('    - "pom.xml"', manifest)
    self.assertIn('    - "build.gradle"', manifest)
    self.assertIn('    - "src/main/java"', manifest)
    self.assertIn(
      'baseline: "code-review/bill-java-code-review/SKILL.md"',
      manifest,
    )
    self.assertIn(
      'declared_quality_check_file: "quality-check/bill-java-quality-check/SKILL.md"',
      manifest,
    )

    review_skill = pack_root / "code-review" / "bill-java-code-review" / "SKILL.md"
    quality_skill = pack_root / "quality-check" / "bill-java-quality-check" / "SKILL.md"
    feature_implement_skill = (
      self.repo / "skills" / "java" / "bill-java-feature-implement" / "SKILL.md"
    )
    feature_verify_skill = (
      self.repo / "skills" / "java" / "bill-java-feature-verify" / "SKILL.md"
    )
    self.assertTrue(review_skill.is_file())
    self.assertTrue(quality_skill.is_file())
    self.assertTrue(feature_implement_skill.is_file())
    self.assertTrue(feature_verify_skill.is_file())

    review_body = review_skill.read_text(encoding="utf-8")
    quality_body = quality_skill.read_text(encoding="utf-8")
    feature_implement_body = feature_implement_skill.read_text(encoding="utf-8")
    feature_verify_body = feature_verify_skill.read_text(encoding="utf-8")
    self.assertIn("## Additional Resources", review_body)
    self.assertIn("[stack-routing.md](stack-routing.md)", review_body)
    self.assertIn("[review-orchestrator.md](review-orchestrator.md)", review_body)
    self.assertIn("[review-delegation.md](review-delegation.md)", review_body)
    self.assertIn("[telemetry-contract.md](telemetry-contract.md)", review_body)
    # SKILL-21 follow-up: platform-pack shells now carry Project Overrides
    # as governance ceremony instead of leaking it into content.md.
    self.assertIn("## Project Overrides", review_body)
    self.assertIn(".agents/skill-overrides.md", review_body)

    self.assertIn("## Additional Resources", quality_body)
    self.assertIn("[stack-routing.md](stack-routing.md)", quality_body)
    self.assertIn("[telemetry-contract.md](telemetry-contract.md)", quality_body)
    self.assertNotIn("## Specialist Scope", quality_body)
    self.assertNotIn("## Outputs Contract", quality_body)
    self.assertIn("## Project Overrides", quality_body)
    self.assertIn("## Project Overrides", feature_implement_body)
    self.assertIn("## Project Overrides", feature_verify_body)
    self.assertTrue(
      any("Applied built-in platform preset for 'java'." in note for note in result.notes)
    )
    self.assertTrue(
      any("Thin feature-implement and feature-verify stubs" in note for note in result.notes)
    )

    self.assertEqual(
      sorted(path.name for path in result.symlinks),
      sorted(
        [
          "review-delegation.md",
          "review-orchestrator.md",
          "stack-routing.md",
          "telemetry-contract.md",
          "stack-routing.md",
          "telemetry-contract.md",
        ]
      ),
    )
    self.assertTrue(any("Quality-check scaffolded by default." in note for note in result.notes))

  def test_platform_pack_full_skeleton(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        skeleton_mode="full",
      )
    )
    self.assertEqual(result.kind, "platform-pack")

    pack_root = self.repo / "platform-packs" / "java"
    manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
    for area in sorted(scaffold_module.APPROVED_CODE_REVIEW_AREAS):
      self.assertIn(f'  - "{area}"', manifest)
      self.assertIn(
        f'    {area}: "code-review/bill-java-code-review-{area}/SKILL.md"',
        manifest,
      )
      skill_md = (
        pack_root
        / "code-review"
        / f"bill-java-code-review-{area}"
        / "SKILL.md"
      )
      self.assertTrue(skill_md.is_file())
      body = skill_md.read_text(encoding="utf-8")
      self.assertIn("## Description", body)
      self.assertNotIn("## Additional Resources", body)

    self.assertTrue(
      any("Full skeleton scaffolded with" in note for note in result.notes)
    )
    expected_created_files = 5 + len(scaffold_module.APPROVED_CODE_REVIEW_AREAS)
    self.assertEqual(len(result.created_files), expected_created_files)

  def test_add_on_flat(self) -> None:
    result = scaffold(
      self._payload(kind="add-on", name="android-new-addon", platform="kmp")
    )
    self.assertEqual(result.kind, "add-on")
    addon_md = self.repo / "platform-packs" / "kmp" / "addons" / "android-new-addon.md"
    self.assertTrue(addon_md.is_file())
    # Add-ons are supporting-file markdown, not governance shells; they must
    # NOT receive the Project Overrides ceremony. The shell they plug into
    # already carries it.
    self.assertNotIn("## Project Overrides", addon_md.read_text(encoding="utf-8"))

  def test_platform_pack_skills_carry_project_overrides(self) -> None:
    """SKILL-21 follow-up: every scaffolded platform-pack SKILL.md renders
    ``## Project Overrides`` as shell governance, keeping overrides
    precedence in SKILL.md instead of leaking into content.md.

    Covers: platform-pack (baseline code-review + shelled quality-check),
    code-review-area specialists, and shelled quality-check overrides.
    """
    scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        skeleton_mode="full",
      )
    )
    pack_root = self.repo / "platform-packs" / "java"

    baseline_body = (pack_root / "code-review" / "bill-java-code-review" / "SKILL.md").read_text(
      encoding="utf-8"
    )
    quality_body = (
      pack_root / "quality-check" / "bill-java-quality-check" / "SKILL.md"
    ).read_text(encoding="utf-8")
    for body in (baseline_body, quality_body):
      self.assertIn("## Project Overrides", body)
      self.assertIn(".agents/skill-overrides.md", body)

    for area in sorted(scaffold_module.APPROVED_CODE_REVIEW_AREAS):
      specialist_body = (
        pack_root / "code-review" / f"bill-java-code-review-{area}" / "SKILL.md"
      ).read_text(encoding="utf-8")
      self.assertIn("## Project Overrides", specialist_body)
      self.assertIn(".agents/skill-overrides.md", specialist_body)
      # Ceremony must NOT leak into the sibling content.md. content.md is
      # author-owned and never carries overrides precedence.
      specialist_content = (
        pack_root / "code-review" / f"bill-java-code-review-{area}" / "content.md"
      ).read_text(encoding="utf-8")
      self.assertNotIn("## Project Overrides", specialist_content)

  def test_description_section_inferred_no_todo(self) -> None:
    """Default `## Description` bodies must be seeded from family/platform/area
    rather than left as `TODO:` markers. Acceptance: the H2 body renders
    plain-English text every kind and never contains a ``TODO`` placeholder.
    """
    code_review_area = scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-kotlin-code-review-performance",
        platform="kotlin",
        area="performance",
      )
    )
    area_body = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-performance"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertIn("## Description", area_body)
    self.assertNotIn("TODO: author the description", area_body)
    self.assertIn("Kotlin", area_body)
    self.assertIn("performance risks", area_body)
    self.assertEqual(code_review_area.kind, "code-review-area")

    scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-php-feature-implement",
        platform="php",
        family="feature-implement",
      )
    )
    feature_body = (
      self.repo / "skills" / "php" / "bill-php-feature-implement" / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("TODO: author the description", feature_body)
    self.assertIn("Php", feature_body)

    scaffold(self._payload(kind="horizontal", name="bill-horizontal-new"))
    horizontal_body = (
      self.repo / "skills" / "bill-horizontal-new" / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("TODO: author the description", horizontal_body)

  def test_code_review_baseline_has_dual_mode_sections(self) -> None:
    """A baseline code-review skill must ship with ``## Delegated Mode`` and
    ``## Inline Mode`` seeds so the skill works regardless of whether the
    pack has declared any specialists yet. Area specialists, quality-check,
    and feature-implement/verify skills MUST NOT get these extra sections.
    """
    scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        skeleton_mode="starter",
      )
    )
    baseline_body = (
      self.repo
      / "platform-packs"
      / "java"
      / "code-review"
      / "bill-java-code-review"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertIn("## Delegated Mode", baseline_body)
    self.assertIn("## Inline Mode", baseline_body)
    self.assertIn("declared_code_review_areas", baseline_body)
    # Specialist Scope must now mention both modes.
    self.assertIn("Delegated", baseline_body)
    self.assertIn("Inline", baseline_body)
    # Dual-mode sections must sit between Outputs Contract and Execution
    # Mode Reporting so the runtime-mode narrative flows naturally.
    outputs_index = baseline_body.index("## Outputs Contract")
    delegated_index = baseline_body.index("## Delegated Mode")
    inline_index = baseline_body.index("## Inline Mode")
    exec_mode_index = baseline_body.index("## Execution Mode Reporting")
    self.assertLess(outputs_index, delegated_index)
    self.assertLess(delegated_index, inline_index)
    self.assertLess(inline_index, exec_mode_index)

    quality_body = (
      self.repo
      / "platform-packs"
      / "java"
      / "quality-check"
      / "bill-java-quality-check"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("## Delegated Mode", quality_body)
    self.assertNotIn("## Inline Mode", quality_body)

    scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-java-code-review-performance",
        platform="java",
        area="performance",
      )
    )
    area_body = (
      self.repo
      / "platform-packs"
      / "java"
      / "code-review"
      / "bill-java-code-review-performance"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("## Delegated Mode", area_body)
    self.assertNotIn("## Inline Mode", area_body)

  def test_code_review_sections_seeded_no_todo(self) -> None:
    """`## Specialist Scope`, `## Inputs`, and `## Outputs Contract` must ship
    with family/area-aware seeds instead of TODO placeholders for code-review
    and feature families. Quality-check's ``## Execution Steps`` /
    ``## Fix Strategy`` intentionally stay as TODOs because the platform
    commands must be hand-authored.
    """
    scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-kotlin-code-review-security",
        platform="kotlin",
        area="security",
      )
    )
    area_body = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-security"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("TODO: author the specialist scope", area_body)
    self.assertNotIn("TODO: author the inputs", area_body)
    self.assertNotIn("TODO: author the outputs contract", area_body)
    self.assertIn("secrets handling", area_body)
    self.assertIn("stack-routing.md", area_body)
    self.assertIn("Findings scoped to", area_body)

    scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-php-feature-verify",
        platform="php",
        family="feature-verify",
      )
    )
    verify_body = (
      self.repo / "skills" / "php" / "bill-php-feature-verify" / "SKILL.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("TODO: author the specialist scope", verify_body)
    self.assertNotIn("TODO: author the inputs", verify_body)
    self.assertNotIn("TODO: author the outputs contract", verify_body)
    self.assertIn("acceptance criteria", verify_body)
    self.assertIn("Pass/fail verdict", verify_body)

    quality_result = scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-kmp-quality-check",
        platform="kmp",
        family="quality-check",
      )
    )
    self.assertEqual(quality_result.kind, "platform-override-piloted")
    quality_body = (
      self.repo
      / "platform-packs"
      / "kmp"
      / "quality-check"
      / "bill-kmp-quality-check"
      / "SKILL.md"
    ).read_text(encoding="utf-8")
    # SKILL-21 pass-2: quality-check Execution Steps + Fix Strategy default
    # bodies now point at the sibling content.md (the pack owns the actual
    # steps + fix strategy) and reference the stack-routing sidecar so the
    # validator finds the required supporting-file mention in the SKILL.md
    # shell itself.
    self.assertNotIn("TODO: author the execution steps", quality_body)
    self.assertNotIn("TODO: author the fix strategy", quality_body)
    self.assertIn("stack-routing.md", quality_body)
    self.assertIn("sibling `content.md`", quality_body)

  def test_pre_shell_family_emits_interim_note(self) -> None:
    # SKILL-16 promoted quality-check onto the shell+content contract, so the
    # pre-shell acceptance case now exercises feature-implement (the other
    # pre-shell family). feature-verify is similar and covered in
    # test_pre_shell_override_passes_real_validate_skill_file.
    result = scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-php-feature-implement",
        platform="php",
        family="feature-implement",
      )
    )
    self.assertEqual(result.kind, "platform-override-piloted")
    skill_md = self.repo / "skills" / "php" / "bill-php-feature-implement" / "SKILL.md"
    self.assertTrue(skill_md.is_file())
    self.assertTrue(any("will move when" in note for note in result.notes))
    # F-001: pre-shell platform overrides land under ``skills/`` and must
    # include Project Overrides + .agents/skill-overrides.md so the real
    # ``validate_skill_file`` accepts them post-scaffold.
    body = skill_md.read_text(encoding="utf-8")
    self.assertIn("## Project Overrides", body)
    self.assertIn(".agents/skill-overrides.md", body)

  def test_shelled_quality_check_family(self) -> None:
    """SKILL-16: quality-check is shelled — scaffolder lands SKILL.md in the pack
    and registers ``declared_quality_check_file`` on the manifest."""
    result = scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-kotlin-quality-check",
        platform="kotlin",
        family="quality-check",
      )
    )
    self.assertEqual(result.kind, "platform-override-piloted")
    skill_md = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "quality-check"
      / "bill-kotlin-quality-check"
      / "SKILL.md"
    )
    self.assertTrue(skill_md.is_file())

    manifest = (self.repo / "platform-packs" / "kotlin" / "platform.yaml").read_text(
      encoding="utf-8"
    )
    self.assertIn(
      "declared_quality_check_file: quality-check/bill-kotlin-quality-check/SKILL.md",
      manifest,
    )

    body = skill_md.read_text(encoding="utf-8")
    # Shelled quality-check content contract requires these five H2s; the
    # three code-review-specific ones (Specialist Scope, Inputs, Outputs
    # Contract) MUST NOT be required here.
    self.assertIn("## Description", body)
    self.assertIn("## Execution Steps", body)
    self.assertIn("## Fix Strategy", body)
    self.assertIn("## Execution Mode Reporting", body)
    self.assertIn("## Telemetry Ceremony Hooks", body)
    self.assertNotIn("## Specialist Scope", body)
    self.assertNotIn("## Outputs Contract", body)
    # SKILL-21 follow-up: shelled platform-pack skills now carry Project
    # Overrides so overrides precedence lives in SKILL.md instead of
    # leaking into content.md.
    self.assertIn("## Project Overrides", body)
    self.assertIn(".agents/skill-overrides.md", body)

  def test_shelled_quality_check_rollback_on_manifest_write_failure(self) -> None:
    """SKILL-16: manifest-write failure for quality-check must roll back atomically."""
    pre_snapshot = _snapshot_tree(self.repo)

    def boom(**_kwargs: object) -> None:
      raise OSError("simulated manifest write failure")

    payload = self._payload(
      kind="platform-override-piloted",
      name="bill-kotlin-quality-check",
      platform="kotlin",
      family="quality-check",
    )

    with mock.patch.object(
      scaffold_manifest_module,
      "set_declared_quality_check_file",
      side_effect=boom,
    ):
      with self.assertRaises((OSError, ScaffoldRollbackError)):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)

  def test_horizontal_passes_real_validate_skill_file(self) -> None:
    """Invoke the actual validator on a horizontal scaffolded SKILL.md.

    F-001 acceptance guard: the validator used by the repo-level validation
    command (``scripts/validate_agent_configs.py``) must accept scaffolder
    output for kinds that land under ``skills/``. Runs
    ``validate_skill_file`` directly rather than spawning the entire
    validator binary, to keep the test hermetic.
    """
    scaffold(self._payload(kind="horizontal", name="bill-horizontal-real-validate"))
    skill_md = self.repo / "skills" / "bill-horizontal-real-validate" / "SKILL.md"

    validate_skill_file = _load_validate_skill_file()
    issues: list[str] = []
    validate_skill_file("bill-horizontal-real-validate", skill_md, issues)
    self.assertEqual(issues, [])

  def test_pre_shell_override_passes_real_validate_skill_file(self) -> None:
    """Same guard as above for a pre-shell platform override."""
    scaffold(
      self._payload(
        kind="platform-override-piloted",
        name="bill-php-feature-verify",
        platform="php",
        family="feature-verify",
      )
    )
    skill_md = (
      self.repo / "skills" / "php" / "bill-php-feature-verify" / "SKILL.md"
    )

    validate_skill_file = _load_validate_skill_file()
    issues: list[str] = []
    validate_skill_file("bill-php-feature-verify", skill_md, issues)
    self.assertEqual(issues, [])


class ScaffoldRejectionTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def test_invalid_payload_missing_kind(self) -> None:
    with self.assertRaises(InvalidScaffoldPayloadError):
      scaffold(
        {
          "scaffold_payload_version": "1.0",
          "name": "bill-smoke",
          "repo_root": str(self.repo),
        }
      )

  def test_wrong_payload_version(self) -> None:
    with self.assertRaises(ScaffoldPayloadVersionMismatchError):
      scaffold(
        {
          "scaffold_payload_version": "9.99",
          "kind": "horizontal",
          "name": "bill-mismatch",
          "repo_root": str(self.repo),
        }
      )

  def test_idempotency_second_run_fails_loudly(self) -> None:
    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "horizontal",
      "name": "bill-twice",
      "repo_root": str(self.repo),
    }
    scaffold(payload)
    with self.assertRaises(SkillAlreadyExistsError):
      scaffold(payload)

  def test_platform_pack_requires_routing_signals_when_no_preset_exists(self) -> None:
    with self.assertRaisesRegex(
      InvalidScaffoldPayloadError,
      "when no built-in platform preset exists for 'custom-jvm'",
    ):
      scaffold(
        {
          "scaffold_payload_version": "1.0",
          "kind": "platform-pack",
          "platform": "custom-jvm",
          "repo_root": str(self.repo),
        }
      )

  def test_platform_pack_rejects_unknown_skeleton_mode(self) -> None:
    with self.assertRaisesRegex(
      InvalidScaffoldPayloadError,
      "field 'skeleton_mode' must be one of",
    ):
      scaffold(
        {
          "scaffold_payload_version": "1.0",
          "kind": "platform-pack",
          "platform": "java",
          "skeleton_mode": "maximal",
          "repo_root": str(self.repo),
        }
      )


class ScaffoldRollbackTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def test_rollback_on_validator_failure_leaves_tree_byte_identical(self) -> None:
    pre_snapshot = _snapshot_tree(self.repo)

    def raise_validator_error(*_args: object, **_kwargs: object) -> None:
      raise ScaffoldValidatorError("simulated validator failure")

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "code-review-area",
      "name": "bill-kotlin-code-review-performance",
      "platform": "kotlin",
      "area": "performance",
      "repo_root": str(self.repo),
    }

    with mock.patch.object(scaffold_module, "_run_validator", side_effect=raise_validator_error):
      with self.assertRaises(ScaffoldValidatorError):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)

  def test_rollback_on_manifest_write_failure(self) -> None:
    pre_snapshot = _snapshot_tree(self.repo)

    def boom(**_kwargs: object) -> None:
      raise OSError("simulated manifest write failure")

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "code-review-area",
      "name": "bill-kotlin-code-review-security",
      "platform": "kotlin",
      "area": "security",
      "repo_root": str(self.repo),
    }

    with mock.patch.object(scaffold_manifest_module, "append_code_review_area", side_effect=boom):
      with self.assertRaises((OSError, ScaffoldRollbackError)):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)

  def test_rollback_on_unregistered_supporting_file(self) -> None:
    """F-002: loud-fail when a runtime-supporting-file has no registered target.

    ``scripts.skill_repo_contracts.RUNTIME_SUPPORTING_FILES`` may not
    reference a file name that is missing from
    ``SUPPORTING_FILE_TARGETS``. Previously the scaffolder silently
    ``continue``'d past the unknown name; the fix raises
    :class:`MissingSupportingFileTargetError` and rolls back the partial
    skill so the tree is byte-identical to pre-run.
    """
    import scripts.skill_repo_contracts as contracts

    skill_name = "bill-missing-support-target"
    pre_snapshot = _snapshot_tree(self.repo)

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "horizontal",
      "name": skill_name,
      "repo_root": str(self.repo),
    }

    patched_runtime = dict(contracts.RUNTIME_SUPPORTING_FILES)
    patched_runtime[skill_name] = ("does-not-exist.md",)
    with mock.patch.object(contracts, "RUNTIME_SUPPORTING_FILES", patched_runtime):
      with self.assertRaises(MissingSupportingFileTargetError):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)
    # The skill directory should also be gone after rollback.
    self.assertFalse((self.repo / "skills" / skill_name).exists())

  def test_rollback_on_symlink_creation_failure(self) -> None:
    pre_snapshot = _snapshot_tree(self.repo)

    original_stage = scaffold_module._stage_sidecar_symlinks

    def boom(*_args: object, **_kwargs: object) -> None:
      raise OSError("simulated symlink failure")

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "horizontal",
      "name": "bill-symlink-rollback",
      "repo_root": str(self.repo),
    }

    with mock.patch.object(scaffold_module, "_stage_sidecar_symlinks", side_effect=boom):
      with self.assertRaises((OSError, ScaffoldRollbackError)):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)
    # original helper stays available for other tests that reimport the module
    self.assertIs(scaffold_module._stage_sidecar_symlinks, original_stage)

  def test_rollback_on_real_symlink_to_failure(self) -> None:
    """F-004: exercise the real ``Path.symlink_to`` failure branch.

    The companion test above monkeypatches ``_stage_sidecar_symlinks``
    wholesale and therefore never runs the real loop inside the helper —
    and the payload's skill name is absent from ``RUNTIME_SUPPORTING_FILES``,
    so in the non-patched flow the helper would early-return with zero
    symlinks anyway. This test covers the branch the patched version
    skips: a synthetic skill name is wired into ``RUNTIME_SUPPORTING_FILES``
    so the helper actually calls ``Path.symlink_to``, and that call is
    patched to raise. The rollback must still leave the tree byte-identical.
    """
    import scripts.skill_repo_contracts as contracts

    skill_name = "bill-symlink-real"
    pre_snapshot = _snapshot_tree(self.repo)

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "horizontal",
      "name": skill_name,
      "repo_root": str(self.repo),
    }

    patched_runtime = dict(contracts.RUNTIME_SUPPORTING_FILES)
    patched_runtime[skill_name] = ("stack-routing.md",)

    def raise_symlink(*_args: object, **_kwargs: object) -> None:
      raise OSError("simulated symlink_to failure")

    # Mutate the module dict in place because ``scaffold._stage_sidecar_symlinks``
    # imports ``RUNTIME_SUPPORTING_FILES`` lazily inside the function body
    # (see ``skill_bill/scaffold.py::_stage_sidecar_symlinks``), so the
    # import happens after the patch is in effect.
    with mock.patch.object(contracts, "RUNTIME_SUPPORTING_FILES", patched_runtime):
      with mock.patch.object(Path, "symlink_to", side_effect=raise_symlink):
        with self.assertRaises((OSError, ScaffoldRollbackError)):
          scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)
    self.assertFalse((self.repo / "skills" / skill_name).exists())


class ScaffolderOwnedSectionsIdenticalTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def test_two_specialists_same_family_same_ceremony_sections(self) -> None:
    specialist_a = scaffold(
      {
        "scaffold_payload_version": "1.0",
        "kind": "code-review-area",
        "name": "bill-kotlin-code-review-testing",
        "platform": "kotlin",
        "area": "testing",
        "repo_root": str(self.repo),
      }
    )
    specialist_b = scaffold(
      {
        "scaffold_payload_version": "1.0",
        "kind": "code-review-area",
        "name": "bill-kotlin-code-review-reliability",
        "platform": "kotlin",
        "area": "reliability",
        "repo_root": str(self.repo),
      }
    )

    body_a = (specialist_a.skill_path / "SKILL.md").read_text(encoding="utf-8")
    body_b = (specialist_b.skill_path / "SKILL.md").read_text(encoding="utf-8")
    owned_a = extract_scaffolder_owned(body_a)
    owned_b = extract_scaffolder_owned(body_b)
    self.assertEqual(
      set(owned_a),
      {"## Execution", "## Execution Mode Reporting", "## Telemetry Ceremony Hooks"},
    )
    self.assertEqual(owned_a, owned_b)


class NewSkillCliErrorMappingTest(unittest.TestCase):
  """F-006: ``new_skill_command`` maps ScaffoldError subclasses to exit codes.

  Previously the handler wrapped every scaffolder failure in ``ValueError``,
  collapsing typed failure modes into exit code 1. The fix catches
  :class:`skill_bill.scaffold_exceptions.ScaffoldError` specifically, prints
  the message to stderr, and returns a stable exit code per concrete
  subclass. This test drives the real handler through an ``argparse.Namespace``
  and asserts the exit code for at least one path.
  """

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def test_invalid_payload_returns_exit_code_2(self) -> None:
    import argparse
    import json as _json
    from skill_bill.cli import new_skill_command

    payload_path = self.tmp_path / "payload.json"
    # Missing ``kind`` triggers InvalidScaffoldPayloadError, which must map
    # to exit code 2 per the exit_code_map in new_skill_command.
    payload_path.write_text(
      _json.dumps(
        {
          "scaffold_payload_version": "1.0",
          "name": "bill-cli-invalid",
          "repo_root": str(self.repo),
        }
      ),
      encoding="utf-8",
    )

    args = argparse.Namespace(
      payload=str(payload_path),
      interactive=False,
      dry_run=False,
      format="json",
    )
    code = new_skill_command(args)
    self.assertEqual(code, 2)


class NewSkillInteractivePromptTest(unittest.TestCase):
  def test_platform_pack_prompt_maps_baseline_only_to_starter(self) -> None:
    from skill_bill.cli import _prompt_new_skill_interactively

    with mock.patch(
      "builtins.input",
      side_effect=[
        "1",      # new platform skill set
        "java",   # platform
        "n",      # include specialists?
        "",       # display name
        "",       # description
        "n",      # governs add-ons?
      ],
    ):
      payload = _prompt_new_skill_interactively()

    self.assertEqual(payload["kind"], "platform-pack")
    self.assertEqual(payload["platform"], "java")
    self.assertEqual(payload["skeleton_mode"], "starter")
    self.assertFalse(payload["governs_addons"])

  def test_platform_pack_prompt_maps_specialists_to_full(self) -> None:
    from skill_bill.cli import _prompt_new_skill_interactively

    with mock.patch(
      "builtins.input",
      side_effect=[
        "1",         # new platform skill set
        "python",    # platform
        "y",         # include specialists?
        "Python",    # display name
        "",          # description
        "pyproject.toml,setup.py",  # strong signals
        "",          # tie-breakers
        "y",         # governs add-ons?
      ],
    ):
      payload = _prompt_new_skill_interactively()

    self.assertEqual(payload["kind"], "platform-pack")
    self.assertEqual(payload["platform"], "python")
    self.assertEqual(payload["skeleton_mode"], "full")
    self.assertEqual(
      payload["routing_signals"]["strong"],
      ["pyproject.toml", "setup.py"],
    )
    self.assertTrue(payload["governs_addons"])


class AgentDetectionTest(unittest.TestCase):
  """Exercise :func:`detect_agents` with 0 / 1 / all five agent dirs present."""

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.fake_home = Path(self._tmpdir.name) / "home"
    self.fake_home.mkdir()

  def test_no_agents_detected(self) -> None:
    self.assertEqual(install_module.detect_agents(home=self.fake_home), [])

  def test_single_agent_detected(self) -> None:
    (self.fake_home / ".claude").mkdir()
    detected = install_module.detect_agents(home=self.fake_home)
    self.assertEqual([target.name for target in detected], ["claude"])

  def test_all_five_agents_detected(self) -> None:
    for subdir in (".copilot", ".claude", ".glm", ".codex", ".config/opencode"):
      (self.fake_home / subdir).mkdir(parents=True)
    detected = install_module.detect_agents(home=self.fake_home)
    self.assertEqual(
      sorted(target.name for target in detected),
      sorted(install_module.SUPPORTED_AGENTS),
    )


class ContentMdSiblingTest(unittest.TestCase):
  """SKILL-21 AC 15(a): scaffolder writes both SKILL.md and content.md.

  Every kind that produces a SKILL.md must also produce a sibling
  ``content.md`` in the same directory. Add-on kind is exempt because it
  writes a flat file.
  """

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def _payload(self, **overrides: object) -> dict:
    payload: dict[str, object] = {
      "scaffold_payload_version": "1.0",
      "repo_root": str(self.repo),
    }
    payload.update(overrides)
    return payload

  def test_horizontal_writes_content_md_sibling(self) -> None:
    scaffold(self._payload(kind="horizontal", name="bill-horizontal-content"))
    skill_dir = self.repo / "skills" / "bill-horizontal-content"
    self.assertTrue((skill_dir / "SKILL.md").is_file())
    self.assertTrue((skill_dir / "content.md").is_file())

  def test_code_review_area_writes_content_md_sibling(self) -> None:
    scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-kotlin-code-review-performance",
        platform="kotlin",
        area="performance",
      )
    )
    skill_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-performance"
    )
    self.assertTrue((skill_dir / "SKILL.md").is_file())
    self.assertTrue((skill_dir / "content.md").is_file())

  def test_platform_pack_writes_content_md_for_every_generated_skill(self) -> None:
    scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        skeleton_mode="starter",
      )
    )
    baseline_dir = (
      self.repo / "platform-packs" / "java" / "code-review" / "bill-java-code-review"
    )
    quality_dir = (
      self.repo / "platform-packs" / "java" / "quality-check" / "bill-java-quality-check"
    )
    feature_implement_dir = self.repo / "skills" / "java" / "bill-java-feature-implement"
    feature_verify_dir = self.repo / "skills" / "java" / "bill-java-feature-verify"
    for directory in [
      baseline_dir,
      quality_dir,
      feature_implement_dir,
      feature_verify_dir,
    ]:
      self.assertTrue((directory / "SKILL.md").is_file(), directory)
      self.assertTrue((directory / "content.md").is_file(), directory)

  def test_add_on_kind_does_not_get_content_md(self) -> None:
    scaffold(
      self._payload(kind="add-on", name="android-new-addon", platform="kmp")
    )
    addon_path = self.repo / "platform-packs" / "kmp" / "addons" / "android-new-addon.md"
    self.assertTrue(addon_path.is_file())
    self.assertFalse(addon_path.with_name("content.md").exists())

  def test_content_body_present_written_verbatim(self) -> None:
    body = (
      "# My skill body\n"
      "\n"
      "Reviews a specific thing.\n"
      "\n"
      "- Step 1\n"
      "- Step 2\n"
    )
    scaffold(
      self._payload(
        kind="horizontal",
        name="bill-horizontal-verbatim",
        content_body=body,
      )
    )
    content_path = self.repo / "skills" / "bill-horizontal-verbatim" / "content.md"
    self.assertEqual(content_path.read_text(encoding="utf-8"), body)

  def test_content_body_absent_writes_placeholder(self) -> None:
    scaffold(
      self._payload(kind="horizontal", name="bill-horizontal-placeholder")
    )
    content_path = self.repo / "skills" / "bill-horizontal-placeholder" / "content.md"
    text = content_path.read_text(encoding="utf-8")
    self.assertIn("bill-horizontal-placeholder", text)
    self.assertIn("TODO", text)

  def test_scaffold_template_is_deterministic(self) -> None:
    first = scaffold(
      self._payload(kind="horizontal", name="bill-horizontal-deterministic-a")
    )
    second_repo = _build_seed_repo(self.tmp_path / "second")
    second = scaffold(
      {
        "scaffold_payload_version": "1.0",
        "kind": "horizontal",
        "name": "bill-horizontal-deterministic-a",
        "repo_root": str(second_repo),
      }
    )
    first_body = (first.skill_path / "SKILL.md").read_bytes()
    second_body = (second.skill_path / "SKILL.md").read_bytes()
    self.assertEqual(first_body, second_body)


class ContentMdRollbackTest(unittest.TestCase):
  """SKILL-21 AC 15(a): rollback removes both siblings on validator failure."""

  def setUp(self) -> None:
    self._tmpdir = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmpdir.cleanup)
    self.tmp_path = Path(self._tmpdir.name)
    self.repo = _build_seed_repo(self.tmp_path)
    self._no_agents = _NoAgentsPatch()
    self._no_agents.__enter__()
    self.addCleanup(self._no_agents.__exit__, None, None, None)

  def test_rollback_removes_content_md_alongside_skill_md(self) -> None:
    pre_snapshot = _snapshot_tree(self.repo)

    def boom(*_args: object, **_kwargs: object) -> None:
      raise ScaffoldValidatorError("simulated validator failure")

    payload = {
      "scaffold_payload_version": "1.0",
      "kind": "code-review-area",
      "name": "bill-kotlin-code-review-performance",
      "platform": "kotlin",
      "area": "performance",
      "repo_root": str(self.repo),
    }

    with mock.patch.object(scaffold_module, "_run_validator", side_effect=boom):
      with self.assertRaises(ScaffoldValidatorError):
        scaffold(payload)

    post_snapshot = _snapshot_tree(self.repo)
    self.assertEqual(pre_snapshot, post_snapshot)
    # Explicitly confirm both files are gone:
    skill_dir = (
      self.repo
      / "platform-packs"
      / "kotlin"
      / "code-review"
      / "bill-kotlin-code-review-performance"
    )
    self.assertFalse((skill_dir / "SKILL.md").exists())
    self.assertFalse((skill_dir / "content.md").exists())


if __name__ == "__main__":
  unittest.main()
