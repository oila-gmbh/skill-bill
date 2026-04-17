"""Fixture-based acceptance and rejection coverage for the new-skill scaffolder.

Mirrors :mod:`tests.test_shell_content_contract` in shape: each test runs
against a ``tmp_path`` scratch repo seeded from
``tests/fixtures/scaffold/seed_repo``. We never mutate the real repository,
never write to ``$HOME``, and always monkeypatch the validator and the
auto-install so a local test run is hermetic.

Covered cases (SKILL-15 AC16):

- Four happy paths: horizontal, platform-override-piloted code-review area,
  add-on flat, pre-shell family override with interim-location note.
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
  (repo / "skills" / "base").mkdir(parents=True)
  # Seed a minimal base capability directory so the repo-level validator
  # (``validate_platform_skill_name``) can resolve pre-shell platform
  # overrides like ``bill-php-feature-verify`` without tripping on missing
  # base capabilities.
  (repo / "skills" / "base" / "bill-feature-verify").mkdir(parents=True)
  (repo / "skills" / "kmp" / "addons").mkdir(parents=True)
  (repo / "skills" / "php").mkdir(parents=True)
  pack_root = repo / "platform-packs" / "kotlin"
  pack_root.mkdir(parents=True)
  (pack_root / "platform.yaml").write_text(_KOTLIN_MANIFEST, encoding="utf-8")
  _seed_skill_file(pack_root / "code-review" / "bill-kotlin-code-review" / "SKILL.md")
  _seed_skill_file(
    pack_root / "code-review" / "bill-kotlin-code-review-architecture" / "SKILL.md"
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
    skill_md = self.repo / "skills" / "base" / "bill-horizontal-new" / "SKILL.md"
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
    # F-001: platform-pack skills go through the lighter
    # validate_platform_pack_skill_file and intentionally do NOT get the
    # Project Overrides boilerplate — keep them lean.
    body = skill_md.read_text(encoding="utf-8")
    self.assertNotIn("## Project Overrides", body)

  def test_add_on_flat(self) -> None:
    result = scaffold(
      self._payload(kind="add-on", name="android-new-addon", platform="kmp")
    )
    self.assertEqual(result.kind, "add-on")
    addon_md = self.repo / "skills" / "kmp" / "addons" / "android-new-addon.md"
    self.assertTrue(addon_md.is_file())

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
    # Platform-pack skills go through the lighter validator and do not get
    # Project Overrides boilerplate — keep them lean.
    self.assertNotIn("## Project Overrides", body)

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
    skill_md = (
      self.repo / "skills" / "base" / "bill-horizontal-real-validate" / "SKILL.md"
    )

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
    self.assertFalse((self.repo / "skills" / "base" / skill_name).exists())

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
    self.assertFalse((self.repo / "skills" / "base" / skill_name).exists())


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
    self.assertEqual(set(owned_a), {"## Execution Mode Reporting", "## Telemetry Ceremony Hooks"})
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


if __name__ == "__main__":
  unittest.main()
