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
from skill_bill.scaffold_template import (  # noqa: E402
  DescriptorMetadata,
  ScaffoldTemplateContext,
  extract_scaffolder_owned,
  render_ceremony_section,
  render_descriptor_section,
)


FIXTURES_ROOT = ROOT / "tests" / "fixtures" / "scaffold"
GOVERNED_CONTENT_AUTHORING_NOTE = (
  "Author skill instructions only in sibling `content.md` files. "
  "Keep scaffold-managed `SKILL.md` wrappers and `shell-ceremony.md` unchanged "
  "unless you are intentionally changing the shared contract."
)


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


def _install_validator_fixture(repo: Path) -> None:
  """Copy the repo validator scripts into a scratch scaffold repo."""
  scripts_dir = repo / "scripts"
  scripts_dir.mkdir(parents=True, exist_ok=True)
  for script_name in ("validate_agent_configs.py", "skill_repo_contracts.py"):
    (scripts_dir / script_name).write_text(
      (ROOT / "scripts" / script_name).read_text(encoding="utf-8"),
      encoding="utf-8",
    )


_KOTLIN_MANIFEST = """\
platform: kotlin
contract_version: "1.1"
display_name: Kotlin

routing_signals:
  strong:
    - ".kt"
  tie_breakers:
    - "fallback tie-breaker"

declared_code_review_areas:
  - architecture

declared_files:
  baseline: code-review/bill-kotlin-code-review/SKILL.md
  areas:
    architecture: code-review/bill-kotlin-code-review-architecture/SKILL.md

area_metadata:
  architecture:
    focus: "architecture, boundaries, and dependency direction"
"""

_KMP_MANIFEST = """\
platform: kmp
contract_version: "1.1"
display_name: KMP

routing_signals:
  strong:
    - "androidMain"
  tie_breakers:
    - "prefer KMP for multiplatform fixtures"

declared_code_review_areas:
  - ui

declared_files:
  baseline: code-review/bill-kmp-code-review/SKILL.md
  areas:
    ui: code-review/bill-kmp-code-review-ui/SKILL.md

area_metadata:
  ui:
    focus: "UI correctness and framework usage"
"""


def _seed_governed_skill(
  repo: Path,
  skill_dir: Path,
  *,
  platform: str,
  display_name: str,
  family: str,
  area: str = "",
  area_focus: str = "",
) -> None:
  """Write a minimal governed skill directory in the new thin-shell shape."""
  scripts_dir = ROOT / "scripts"
  if str(scripts_dir) not in sys.path:
    sys.path.insert(0, str(scripts_dir))
  from scripts.skill_repo_contracts import required_supporting_files_for_skill  # noqa: WPS433

  skill_dir.mkdir(parents=True, exist_ok=True)
  skill_name = skill_dir.name
  descriptor = render_descriptor_section(
    ScaffoldTemplateContext(
      skill_name=skill_name,
      family=family,
      platform=platform,
      area=area,
      display_name=display_name,
    ),
    metadata=DescriptorMetadata(area_focus=area_focus),
  )
  (skill_dir / "SKILL.md").write_text(
    "---\n"
    f"name: {skill_name}\n"
    "description: Fixture content.\n"
    "---\n\n"
    f"{descriptor}\n"
    "## Execution\n\n"
    "Follow the instructions in [content.md](content.md).\n\n"
    f"{render_ceremony_section(ScaffoldTemplateContext(skill_name=skill_name, family=family, platform=platform, area=area, display_name=display_name))}",
    encoding="utf-8",
  )
  (skill_dir / "content.md").write_text(
    "# Fixture Content\n\n"
    "TODO: author the governed content body.\n",
    encoding="utf-8",
  )
  targets = {
    "review-scope.md": repo / "orchestration" / "review-scope" / "PLAYBOOK.md",
    "shell-ceremony.md": repo / "orchestration" / "shell-content-contract" / "shell-ceremony.md",
    "telemetry-contract.md": repo / "orchestration" / "telemetry-contract" / "PLAYBOOK.md",
    "review-orchestrator.md": repo / "orchestration" / "review-orchestrator" / "PLAYBOOK.md",
    "specialist-contract.md": repo / "orchestration" / "review-orchestrator" / "specialist-contract.md",
    "review-delegation.md": repo / "orchestration" / "review-delegation" / "PLAYBOOK.md",
    "stack-routing.md": repo / "orchestration" / "stack-routing" / "PLAYBOOK.md",
    "android-compose-review.md": repo / "platform-packs" / "kmp" / "addons" / "android-compose-review.md",
    "android-navigation-review.md": repo / "platform-packs" / "kmp" / "addons" / "android-navigation-review.md",
    "android-interop-review.md": repo / "platform-packs" / "kmp" / "addons" / "android-interop-review.md",
    "android-design-system-review.md": repo / "platform-packs" / "kmp" / "addons" / "android-design-system-review.md",
    "android-r8-review.md": repo / "platform-packs" / "kmp" / "addons" / "android-r8-review.md",
    "android-compose-edge-to-edge.md": repo / "platform-packs" / "kmp" / "addons" / "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md": repo / "platform-packs" / "kmp" / "addons" / "android-compose-adaptive-layouts.md",
  }
  for file_name in required_supporting_files_for_skill(skill_name):
    (skill_dir / file_name).symlink_to(targets[file_name])


def _build_seed_repo(tmp_path: Path) -> Path:
  """Seed a minimal scratch repo layout that the scaffolder can operate on.

  We intentionally avoid copying the real repo — tests must not depend on
  the main tree for correctness.
  """
  repo = tmp_path / "repo"
  (repo / "skills").mkdir(parents=True)
  shell_ceremony = repo / "orchestration" / "shell-content-contract" / "shell-ceremony.md"
  shell_ceremony.parent.mkdir(parents=True, exist_ok=True)
  shell_ceremony.write_text(
    "## Project Overrides\n\n"
    "If `.agents/skill-overrides.md` exists in the project root and contains a matching section, "
    "read that section and apply it as the highest-priority instruction for this skill.\n\n"
    "## Inputs\n\n"
    "Fixture shell ceremony inputs.\n\n"
    "## Execution Mode Reporting\n\n"
    "Execution mode: inline | delegated\n\n"
    "## Telemetry Ceremony Hooks\n\n"
    "Follow `telemetry-contract.md` when it is present.\n",
    encoding="utf-8",
  )
  telemetry_contract = repo / "orchestration" / "telemetry-contract" / "PLAYBOOK.md"
  telemetry_contract.parent.mkdir(parents=True, exist_ok=True)
  telemetry_contract.write_text(
    "# Telemetry Contract\n\n"
    "Fixture telemetry contract.\n",
    encoding="utf-8",
  )
  review_orchestrator = repo / "orchestration" / "review-orchestrator" / "PLAYBOOK.md"
  review_orchestrator.parent.mkdir(parents=True, exist_ok=True)
  review_orchestrator.write_text(
    "# Review Orchestrator\n\n"
    "Review session ID: <review-session-id>\n"
    "Review run ID: <review-run-id>\n"
    "Applied learnings: none | <learning references>\n",
    encoding="utf-8",
  )
  review_specialist_contract = repo / "orchestration" / "review-orchestrator" / "specialist-contract.md"
  review_specialist_contract.write_text(
    "# Shared Specialist Contract\n\nFixture specialist contract.\n",
    encoding="utf-8",
  )
  review_delegation = repo / "orchestration" / "review-delegation" / "PLAYBOOK.md"
  review_delegation.parent.mkdir(parents=True, exist_ok=True)
  review_delegation.write_text(
    "# Review Delegation\n\nFixture delegation contract.\n",
    encoding="utf-8",
  )
  review_scope = repo / "orchestration" / "review-scope" / "PLAYBOOK.md"
  review_scope.parent.mkdir(parents=True, exist_ok=True)
  review_scope.write_text(
    "# Review Scope\n\nFixture scope contract.\n",
    encoding="utf-8",
  )
  stack_routing = repo / "orchestration" / "stack-routing" / "PLAYBOOK.md"
  stack_routing.parent.mkdir(parents=True, exist_ok=True)
  stack_routing.write_text(
    "# Stack Routing\n\nFixture routing contract.\n",
    encoding="utf-8",
  )
  kmp_addons = repo / "platform-packs" / "kmp" / "addons"
  kmp_addons.mkdir(parents=True, exist_ok=True)
  for addon_name in (
    "android-compose-review.md",
    "android-navigation-review.md",
    "android-interop-review.md",
    "android-design-system-review.md",
    "android-r8-review.md",
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  ):
    (kmp_addons / addon_name).write_text(f"# {addon_name}\n", encoding="utf-8")
  # Seed a minimal base capability directory so the repo-level validator
  # (``validate_platform_skill_name``) can resolve pre-shell platform
  # overrides like ``bill-php-feature-verify`` without tripping on missing
  # base capabilities.
  (repo / "skills" / "bill-feature-verify").mkdir(parents=True)
  (repo / "skills" / "php").mkdir(parents=True)
  kotlin_pack_root = repo / "platform-packs" / "kotlin"
  kotlin_pack_root.mkdir(parents=True)
  (kotlin_pack_root / "platform.yaml").write_text(_KOTLIN_MANIFEST, encoding="utf-8")
  _seed_governed_skill(
    repo,
    kotlin_pack_root / "code-review" / "bill-kotlin-code-review",
    platform="kotlin",
    display_name="Kotlin",
    family="code-review",
  )
  _seed_governed_skill(
    repo,
    kotlin_pack_root / "code-review" / "bill-kotlin-code-review-architecture",
    platform="kotlin",
    display_name="Kotlin",
    family="code-review",
    area="architecture",
    area_focus="architecture, boundaries, and dependency direction",
  )
  kmp_pack_root = repo / "platform-packs" / "kmp"
  kmp_pack_root.mkdir(parents=True, exist_ok=True)
  (kmp_pack_root / "platform.yaml").write_text(_KMP_MANIFEST, encoding="utf-8")
  _seed_governed_skill(
    repo,
    kmp_pack_root / "code-review" / "bill-kmp-code-review",
    platform="kmp",
    display_name="KMP",
    family="code-review",
  )
  _seed_governed_skill(
    repo,
    kmp_pack_root / "code-review" / "bill-kmp-code-review-ui",
    platform="kmp",
    display_name="KMP",
    family="code-review",
    area="ui",
    area_focus="UI correctness and framework usage",
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
    self.assertIn("## Descriptor", body)
    self.assertIn("## Execution", body)
    self.assertIn("## Ceremony", body)
    self.assertNotIn("## Description", body)
    self.assertNotIn("## Execution Mode Reporting", body)
    self.assertNotIn("## Project Overrides", body)
    self.assertNotIn(".agents/skill-overrides.md", body)
    content_md = skill_md.parent / "content.md"
    self.assertTrue(content_md.is_file())
    content_body = content_md.read_text(encoding="utf-8")
    for ceremony_h2 in ("## Project Overrides", "## Execution", "## Ceremony"):
      self.assertNotIn(ceremony_h2, content_body)

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
    self.assertIn("## Descriptor", body)
    self.assertIn("## Execution", body)
    self.assertIn("## Ceremony", body)
    self.assertNotIn("## Project Overrides", body)

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
    self.assertTrue(review_skill.is_file())
    self.assertTrue(quality_skill.is_file())

    review_body = review_skill.read_text(encoding="utf-8")
    review_content = (review_skill.parent / "content.md").read_text(encoding="utf-8")
    quality_body = quality_skill.read_text(encoding="utf-8")
    quality_content = (quality_skill.parent / "content.md").read_text(encoding="utf-8")
    self.assertIn("## Descriptor", review_body)
    self.assertIn("## Execution", review_body)
    self.assertIn("## Ceremony", review_body)
    self.assertNotIn("## Additional Resources", review_content)
    self.assertIn("[review-scope.md](review-scope.md)", review_body)
    self.assertNotIn(
      "Resolve the scope before reviewing. If the caller asks for staged changes, ",
      review_body,
    )
    self.assertIn("## Review Focus", review_content)
    self.assertIn("## Review Guidance", review_content)
    self.assertNotIn("## Project Signals", review_content)
    self.assertIn("[specialist-contract.md](specialist-contract.md)", review_body)
    self.assertNotIn("review-scope.md", review_content)
    self.assertNotIn("specialist-contract.md", review_content)
    self.assertNotIn("## Setup", review_content)
    self.assertNotIn(
      "Resolve the scope before reviewing. If the caller asks for staged changes, "
      "inspect only the staged diff and keep unstaged edits out of findings except "
      "for repo markers needed for classification.",
      review_content,
    )
    self.assertIn("[stack-routing.md](stack-routing.md)", review_body)
    self.assertIn("[review-orchestrator.md](review-orchestrator.md)", review_body)
    self.assertIn("[review-delegation.md](review-delegation.md)", review_body)
    self.assertIn("[telemetry-contract.md](telemetry-contract.md)", review_body)
    self.assertNotIn("## Project Overrides", review_body)

    self.assertIn("## Descriptor", quality_body)
    self.assertIn("## Execution", quality_body)
    self.assertIn("## Ceremony", quality_body)
    self.assertIn("## Execution Steps", quality_content)
    self.assertIn("## Fix Strategy", quality_content)
    self.assertNotIn("## Description", quality_content)
    self.assertNotIn("## Specialist Scope", quality_content)
    self.assertNotIn("## Outputs Contract", quality_content)
    self.assertTrue(
      any("Applied built-in platform preset for 'java'." in note for note in result.notes)
    )
    self.assertIn(GOVERNED_CONTENT_AUTHORING_NOTE, result.notes)

    symlink_names = sorted(path.name for path in result.symlinks)
    self.assertIn("review-scope.md", symlink_names)
    self.assertIn("shell-ceremony.md", symlink_names)
    self.assertIn("stack-routing.md", symlink_names)
    self.assertIn("telemetry-contract.md", symlink_names)
    self.assertTrue(any("Quality-check scaffolded by default." in note for note in result.notes))

  def test_platform_pack_php_uses_built_in_preset(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="php",
      )
    )
    self.assertEqual(result.kind, "platform-pack")

    pack_root = self.repo / "platform-packs" / "php"
    manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
    self.assertIn('platform: "php"', manifest)
    self.assertIn('display_name: "PHP"', manifest)
    self.assertIn('    - "composer.json"', manifest)
    self.assertIn('    - ".php"', manifest)
    self.assertIn('    - "phpunit.xml"', manifest)
    self.assertIn(
      "Prefer PHP when Composer metadata or .php source files dominate mixed backend signals.",
      manifest,
    )
    self.assertTrue(
      any("Applied built-in platform preset for 'php'." in note for note in result.notes)
    )
    self.assertIn(GOVERNED_CONTENT_AUTHORING_NOTE, result.notes)

  def test_platform_pack_validation_ignores_unrelated_existing_pack_drift(self) -> None:
    _install_validator_fixture(self.repo)
    drifted_skill = (
      self.repo
      / "platform-packs"
      / "kmp"
      / "code-review"
      / "bill-kmp-code-review"
      / "SKILL.md"
    )
    drifted_skill.write_text(
      drifted_skill.read_text(encoding="utf-8") + "\nDrift injected outside the new php pack.\n",
      encoding="utf-8",
    )

    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="php",
      )
    )

    self.assertEqual(result.kind, "platform-pack")
    self.assertTrue((self.repo / "platform-packs" / "php" / "platform.yaml").is_file())

  def test_platform_pack_defaults_to_full_skeleton(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
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
      self.assertIn("## Descriptor", body)
      self.assertIn("## Execution", body)
      self.assertIn("## Ceremony", body)
      self.assertNotIn("## Additional Resources", body)

    self.assertTrue(
      any("Full skeleton scaffolded with" in note for note in result.notes)
    )
    expected_created_files = 5 + (2 * len(scaffold_module.APPROVED_CODE_REVIEW_AREAS))
    self.assertEqual(len(result.created_files), expected_created_files)

  def test_platform_pack_can_scaffold_custom_specialist_subset(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="php",
        specialist_areas=["architecture", "security", "testing"],
      )
    )
    self.assertEqual(result.kind, "platform-pack")

    pack_root = self.repo / "platform-packs" / "php"
    manifest = (pack_root / "platform.yaml").read_text(encoding="utf-8")
    self.assertIn('  - "architecture"', manifest)
    self.assertIn('  - "security"', manifest)
    self.assertIn('  - "testing"', manifest)
    self.assertNotIn('  - "ui"', manifest)
    self.assertNotIn('  - "ux-accessibility"', manifest)
    self.assertTrue(
      (pack_root / "code-review" / "bill-php-code-review-architecture" / "SKILL.md").is_file()
    )
    self.assertTrue(
      (pack_root / "code-review" / "bill-php-code-review-security" / "SKILL.md").is_file()
    )
    self.assertTrue(
      (pack_root / "code-review" / "bill-php-code-review-testing" / "SKILL.md").is_file()
    )
    self.assertFalse((pack_root / "code-review" / "bill-php-code-review-ui").exists())
    self.assertTrue(
      any("Custom skeleton scaffolded with 3 approved code-review area stubs." in note for note in result.notes)
    )

  def test_add_on_flat(self) -> None:
    result = scaffold(
      self._payload(kind="add-on", name="android-new-addon", platform="kmp")
    )
    self.assertEqual(result.kind, "add-on")
    addon_md = self.repo / "platform-packs" / "kmp" / "addons" / "android-new-addon.md"
    self.assertTrue(addon_md.is_file())

  def test_add_on_uses_explicit_body_when_provided(self) -> None:
    body = "# android-new-addon\n\nPack-owned guidance.\n"
    scaffold(
      self._payload(
        kind="add-on",
        name="android-new-addon",
        platform="kmp",
        body=body,
      )
    )
    addon_md = self.repo / "platform-packs" / "kmp" / "addons" / "android-new-addon.md"
    self.assertEqual(addon_md.read_text(encoding="utf-8"), body)

  def test_description_section_inferred_no_todo(self) -> None:
    """Governed descriptions now live in the thin shell descriptor, not content."""
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
    self.assertIn("## Descriptor", area_body)
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
    """Governed content must stay author-owned and free of shell boilerplate."""
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
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("## Delegated Mode", baseline_body)
    self.assertNotIn("## Inline Mode", baseline_body)
    self.assertNotIn("## Outputs Contract", baseline_body)
    self.assertNotIn("review-scope.md", baseline_body)
    self.assertIn("## Review Focus", baseline_body)
    self.assertIn("## Review Guidance", baseline_body)
    self.assertNotIn("## Project Signals", baseline_body)
    self.assertNotIn("specialist-contract.md", baseline_body)

    quality_body = (
      self.repo
      / "platform-packs"
      / "java"
      / "quality-check"
      / "bill-java-quality-check"
      / "content.md"
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
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("## Delegated Mode", area_body)
    self.assertNotIn("## Inline Mode", area_body)
    self.assertNotIn("## Specialist Scope", area_body)

  def test_code_review_sections_seeded_no_todo(self) -> None:
    """Governed content should no longer ship duplicated shell contract sections."""
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
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertIn("## Focus", area_body)
    self.assertIn("## Review Triggers", area_body)
    self.assertIn("## Review Guidance", area_body)
    self.assertNotIn("## Description", area_body)
    self.assertNotIn("## Specialist Scope", area_body)
    self.assertNotIn("## Inputs", area_body)
    self.assertNotIn("## Outputs Contract", area_body)
    self.assertNotIn("telemetry-contract.md", area_body)

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
    self.assertIn("## Descriptor", verify_body)
    self.assertIn("## Execution", verify_body)
    self.assertIn("## Ceremony", verify_body)
    verify_content = (
      self.repo / "skills" / "php" / "bill-php-feature-verify" / "content.md"
    ).read_text(encoding="utf-8")
    self.assertNotIn("TODO: author the specialist scope", verify_content)
    self.assertNotIn("TODO: author the inputs", verify_content)
    self.assertNotIn("TODO: author the outputs contract", verify_content)

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
      / "content.md"
    ).read_text(encoding="utf-8")
    self.assertIn("## Purpose", quality_body)
    self.assertIn("## Execution Steps", quality_body)
    self.assertIn("## Fix Strategy", quality_body)

  def _validate_codex_stub(self, toml_path: Path) -> None:
    import tomllib

    self.assertTrue(toml_path.is_file(), f"missing codex stub at {toml_path}")
    with toml_path.open("rb") as handle:
      parsed = tomllib.load(handle)
    for field in ("name", "description", "developer_instructions"):
      self.assertIn(field, parsed)
      self.assertIsInstance(parsed[field], str)
      self.assertTrue(parsed[field].strip())
    self.assertEqual(parsed["name"], toml_path.stem)
    self.assertNotIn("\n", parsed["description"])
    forbidden = (
      "Agent(subagent_type=",
      "Task tool",
      "subagent_type=",
      "Agent tool",
      "general-purpose",
      "@agent-",
    )
    instructions = parsed["developer_instructions"]
    for token in forbidden:
      self.assertNotIn(token, instructions, f"{toml_path} contains forbidden token '{token}'")

  def _validate_opencode_stub(self, md_path: Path) -> None:
    self.assertTrue(md_path.is_file(), f"missing opencode stub at {md_path}")
    text = md_path.read_text(encoding="utf-8")
    lines = text.splitlines()
    self.assertEqual(lines[0], "---", f"{md_path} missing frontmatter open")
    end_index = None
    for i, line in enumerate(lines[1:], start=1):
      if line == "---":
        end_index = i
        break
    self.assertIsNotNone(end_index, f"{md_path} unclosed frontmatter")
    frontmatter: dict[str, str] = {}
    for line in lines[1:end_index]:
      if not line.strip() or line.lstrip().startswith("#"):
        continue
      key, _, value = line.partition(":")
      frontmatter[key.strip()] = value.strip().strip('"').strip("'")
    for field in ("name", "description", "mode"):
      self.assertIn(field, frontmatter, f"{md_path} missing {field}")
      self.assertTrue(frontmatter[field].strip())
    self.assertEqual(frontmatter["mode"], "subagent")
    self.assertEqual(frontmatter["name"], md_path.stem)
    self.assertNotIn("\n", frontmatter["description"])
    body = "\n".join(lines[end_index + 1 :])
    self.assertTrue(body.strip())
    forbidden = (
      "Agent(subagent_type=",
      "Task tool",
      "subagent_type=",
      "Agent tool",
      "general-purpose",
      "@agent-",
    )
    for token in forbidden:
      self.assertNotIn(token, text, f"{md_path} contains forbidden token '{token}'")

  def test_horizontal_with_subagent_specialists_emits_stubs_and_notes(self) -> None:
    result = scaffold(
      self._payload(
        kind="horizontal",
        name="bill-foo-orchestrator",
        subagent_specialists=["foo-arch", "foo-perf"],
      )
    )
    self.assertEqual(result.kind, "horizontal")
    skill_dir = self.repo / "skills" / "bill-foo-orchestrator"
    content_md = skill_dir / "content.md"
    self.assertTrue(content_md.is_file())
    content_text = content_md.read_text(encoding="utf-8")
    self.assertIn("## Subagent Spawn Runtime Notes", content_text)
    self.assertIn("`@foo-arch`", content_text)
    self.assertIn("`@foo-perf`", content_text)

    self._validate_codex_stub(skill_dir / "codex-agents" / "foo-arch.toml")
    self._validate_codex_stub(skill_dir / "codex-agents" / "foo-perf.toml")
    self._validate_opencode_stub(skill_dir / "opencode-agents" / "foo-arch.md")
    self._validate_opencode_stub(skill_dir / "opencode-agents" / "foo-perf.md")

    self.assertTrue(
      any("Subagent stubs emitted: 2." in note for note in result.notes),
      result.notes,
    )

  def test_platform_pack_with_subagent_specialists_attaches_to_baseline_only(self) -> None:
    result = scaffold(
      self._payload(
        kind="platform-pack",
        platform="java",
        subagent_specialists=["arch", "perf"],
      )
    )
    self.assertEqual(result.kind, "platform-pack")
    pack_root = self.repo / "platform-packs" / "java"
    baseline_dir = pack_root / "code-review" / "bill-java-code-review"
    quality_check_dir = pack_root / "quality-check" / "bill-java-quality-check"

    self._validate_codex_stub(baseline_dir / "codex-agents" / "arch.toml")
    self._validate_codex_stub(baseline_dir / "codex-agents" / "perf.toml")
    self._validate_opencode_stub(baseline_dir / "opencode-agents" / "arch.md")
    self._validate_opencode_stub(baseline_dir / "opencode-agents" / "perf.md")

    self.assertFalse((quality_check_dir / "codex-agents").exists())
    self.assertFalse((quality_check_dir / "opencode-agents").exists())
    for area in scaffold_module.APPROVED_CODE_REVIEW_AREAS:
      specialist_dir = pack_root / "code-review" / f"bill-java-code-review-{area}"
      self.assertFalse((specialist_dir / "codex-agents").exists())
      self.assertFalse((specialist_dir / "opencode-agents").exists())

    baseline_content = (baseline_dir / "content.md").read_text(encoding="utf-8")
    self.assertIn("## Subagent Spawn Runtime Notes", baseline_content)

  def test_horizontal_no_subagents_opt_out_skips_emission(self) -> None:
    scaffold(
      self._payload(
        kind="horizontal",
        name="bill-bar-orchestrator",
        no_subagents=True,
      )
    )
    skill_dir = self.repo / "skills" / "bill-bar-orchestrator"
    self.assertFalse((skill_dir / "codex-agents").exists())
    self.assertFalse((skill_dir / "opencode-agents").exists())
    content_text = (skill_dir / "content.md").read_text(encoding="utf-8")
    self.assertNotIn("## Subagent Spawn Runtime Notes", content_text)

  def test_no_subagents_with_specialists_rejected(self) -> None:
    with self.assertRaisesRegex(
      InvalidScaffoldPayloadError,
      "no_subagents=true",
    ):
      scaffold(
        self._payload(
          kind="horizontal",
          name="bill-mixed-orchestrator",
          subagent_specialists=["foo"],
          no_subagents=True,
        )
      )

  def test_subagent_specialists_rejected_for_code_review_area(self) -> None:
    with self.assertRaisesRegex(
      InvalidScaffoldPayloadError,
      "subagent_specialists is only valid for orchestrator kinds",
    ):
      scaffold(
        self._payload(
          kind="code-review-area",
          name="bill-kotlin-code-review-performance",
          platform="kotlin",
          area="performance",
          subagent_specialists=["x"],
        )
      )

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
    body = skill_md.read_text(encoding="utf-8")
    self.assertIn("## Descriptor", body)
    self.assertIn("## Execution", body)
    self.assertIn("## Ceremony", body)
    self.assertNotIn("## Project Overrides", body)
    self.assertTrue((skill_md.parent / "shell-ceremony.md").exists())
    self.assertTrue((skill_md.parent / "telemetry-contract.md").exists())

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
    content = (skill_md.parent / "content.md").read_text(encoding="utf-8")
    self.assertIn("## Descriptor", body)
    self.assertIn("## Execution", body)
    self.assertIn("## Ceremony", body)
    self.assertIn("## Execution Steps", content)
    self.assertIn("## Fix Strategy", content)
    self.assertNotIn("## Description", content)
    self.assertNotIn("## Specialist Scope", content)
    self.assertNotIn("## Outputs Contract", content)
    self.assertNotIn("## Project Overrides", body)
    self.assertIn(GOVERNED_CONTENT_AUTHORING_NOTE, result.notes)

  def test_code_review_area_reports_content_md_authoring_note(self) -> None:
    result = scaffold(
      self._payload(
        kind="code-review-area",
        name="bill-kotlin-code-review-reliability",
        platform="kotlin",
        area="reliability",
      )
    )
    self.assertIn(GOVERNED_CONTENT_AUTHORING_NOTE, result.notes)

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
    self.assertTrue((skill_md.parent / "shell-ceremony.md").exists())
    self.assertTrue((skill_md.parent / "telemetry-contract.md").exists())

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
    self.assertEqual(set(owned_a), {"## Execution", "## Ceremony"})
    self.assertEqual(owned_a, owned_b)


class AgentDetectionTest(unittest.TestCase):
  """Exercise normal skill-target and explicit native subagent detection."""

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

  def test_opencode_detect_agents_returns_only_skill_target(self) -> None:
    (self.fake_home / ".config/opencode").mkdir(parents=True)
    detected = install_module.detect_agents(home=self.fake_home)
    self.assertEqual(
      detected,
      [
        AgentTarget(
          name="opencode",
          path=self.fake_home / ".config" / "opencode" / "skills",
        ),
      ],
    )

  def test_opencode_agents_target_detected_explicitly(self) -> None:
    (self.fake_home / ".config/opencode").mkdir(parents=True)
    self.assertEqual(
      install_module.detect_opencode_agents_target(home=self.fake_home),
      AgentTarget(
        name=install_module.OPENCODE_AGENTS_KIND,
        path=self.fake_home / ".config" / "opencode" / "agents",
      ),
    )

  def test_all_four_agents_detected(self) -> None:
    for subdir in (".copilot", ".claude", ".codex", ".config/opencode"):
      (self.fake_home / subdir).mkdir(parents=True)
    detected = install_module.detect_agents(home=self.fake_home)
    self.assertEqual(
      sorted(target.name for target in detected),
      sorted(install_module.SUPPORTED_AGENTS),
    )


class RenderUpgradeTargetsHorizontalFamilyTest(unittest.TestCase):
  """Regression for F-001: upgrade renderer must emit canonical-taxonomy families.

  After SKILL-31, every horizontal SKILL.md declares either ``workflow`` or
  ``advisor`` as its family. The upgrade renderer must produce the same
  values; otherwise ``skill-bill upgrade`` would silently overwrite the
  migrated descriptors with stale slug-based families.
  """

  EXPECTED_FAMILIES: dict[str, str] = {
    "bill-feature-implement": "workflow",
    "bill-feature-verify": "workflow",
    "bill-grill-plan": "advisor",
    "bill-boundary-decisions": "advisor",
    "bill-boundary-history": "advisor",
    "bill-pr-description": "advisor",
    "bill-create-skill": "advisor",
    "bill-skill-remove": "advisor",
    "bill-feature-guard": "advisor",
    "bill-feature-guard-cleanup": "advisor",
    "bill-unit-test-value-check": "advisor",
    "bill-code-review": "advisor",
    "bill-quality-check": "advisor",
  }

  def test_renderer_emits_new_taxonomy_family_for_each_horizontal_skill(self) -> None:
    from skill_bill.upgrade import render_upgrade_targets

    skills_root = ROOT / "skills"
    rendered = render_upgrade_targets(ROOT)
    for skill_name, expected_family in self.EXPECTED_FAMILIES.items():
      skill_file = skills_root / skill_name / "SKILL.md"
      self.assertIn(
        skill_file,
        rendered,
        msg=f"render_upgrade_targets did not emit a target for {skill_name}",
      )
      family_line = f"Family: `{expected_family}`"
      self.assertIn(
        family_line,
        rendered[skill_file],
        msg=(
          f"Rendered SKILL.md for {skill_name} should declare "
          f"'{family_line}' but produced:\n{rendered[skill_file]}"
        ),
      )

  def test_rendered_family_matches_existing_skill_md(self) -> None:
    from skill_bill.upgrade import render_upgrade_targets

    skills_root = ROOT / "skills"
    rendered = render_upgrade_targets(ROOT)
    for skill_name in self.EXPECTED_FAMILIES:
      skill_file = skills_root / skill_name / "SKILL.md"
      current = skill_file.read_text(encoding="utf-8")
      current_family = _extract_family_line(current)
      rendered_family = _extract_family_line(rendered[skill_file])
      self.assertEqual(
        current_family,
        rendered_family,
        msg=(
          f"Rendered family for {skill_name} drifts from on-disk SKILL.md: "
          f"current='{current_family}' rendered='{rendered_family}'"
        ),
      )


def _extract_family_line(text: str) -> str:
  for line in text.splitlines():
    if line.startswith("Family:"):
      return line
  return ""


if __name__ == "__main__":
  unittest.main()
