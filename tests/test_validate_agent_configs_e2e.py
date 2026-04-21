from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))

from skill_bill.scaffold_template import (  # noqa: E402
  DescriptorMetadata,
  ScaffoldTemplateContext,
  render_ceremony_section,
  render_descriptor_section,
)
from skill_bill.constants import SHELL_CONTRACT_VERSION  # noqa: E402
from skill_repo_contracts import (  # noqa: E402
  ADDON_SUPPORTING_FILE_TARGETS,
  APPLIED_LEARNINGS_PLACEHOLDER,
  CHILD_METADATA_HANDOFF_RULE,
  CHILD_NO_IMPORT_RULE,
  CHILD_NO_TRIAGE_RULE,
  INLINE_TELEMETRY_CONTRACT_MARKERS,
  NO_FINDINGS_TRIAGE_RULE,
  PARENT_IMPORT_RULE,
  PARENT_TRIAGE_RULE,
  REVIEW_RUN_ID_FORMAT,
  REVIEW_RUN_ID_PLACEHOLDER,
  REVIEW_SESSION_ID_FORMAT,
  REVIEW_SESSION_ID_PLACEHOLDER,
  RISK_REGISTER_FINDING_FORMAT,
  TELEMETRY_OWNERSHIP_HEADING,
  TRIAGE_OWNERSHIP_HEADING,
  required_supporting_files_for_skill,
  supporting_file_targets,
)


VALIDATOR_PATH = Path(__file__).resolve().parents[1] / "scripts" / "validate_agent_configs.py"


class ValidateAgentConfigsE2ETest(unittest.TestCase):
  maxDiff = None

  def test_accepts_platform_override_of_dynamic_base_capability(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("ruby", "bill-ruby-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_approved_platform_code_review_specialization(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("java", "bill-java-code-review-security"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_external_platform_override_of_dynamic_base_capability(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("java", "bill-java-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_agent_config_platform_override_of_dynamic_base_capability(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("agent-config", "bill-agent-config-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_rejects_platform_only_capability_name(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("ruby", "bill-ruby-laravel-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn(
        "platform skill 'bill-ruby-laravel-ship-it' must either override an approved base skill",
        result.stdout,
      )

  def test_rejects_unapproved_code_review_area(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("java", "bill-java-code-review-laravel"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("code-review specialization 'laravel' is not approved", result.stdout)

  def test_rejects_external_platform_only_capability_name(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("java", "bill-java-gin-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn(
        "platform skill 'bill-java-gin-ship-it' must either override an approved base skill",
        result.stdout,
      )

  def test_accepts_scaffolded_platform_package_without_matching_pack_root(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("ruby", "bill-ruby-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_governed_addon_files_under_stack_addons_dir(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)
      self.assertIn("12 governed add-on files", result.stdout)

  def test_accepts_governed_addon_files_with_future_expansion_names(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      bare_addon = repo_root / "platform-packs" / "kmp" / "addons" / "eloquent.md"
      area_scoped_addon = repo_root / "platform-packs" / "kmp" / "addons" / "eloquent-persistence.md"
      bare_addon.parent.mkdir(parents=True, exist_ok=True)
      bare_addon.write_text("# valid\n", encoding="utf-8")
      area_scoped_addon.write_text("# valid\n", encoding="utf-8")
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)
      self.assertIn("14 governed add-on files", result.stdout)

  def test_rejects_governed_addon_under_base_package(self) -> None:
    with self.fixture_repo([("base", "bill-feature-implement")]) as repo_root:
      path = repo_root / "skills" / "base" / "addons" / "android-compose-review.md"
      path.parent.mkdir(parents=True, exist_ok=True)
      path.write_text("# invalid\n", encoding="utf-8")
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("governed add-ons must be pack-owned", result.stdout)

  def test_rejects_governed_addon_with_invalid_filename_shape(self) -> None:
    with self.fixture_repo([("base", "bill-feature-implement")]) as repo_root:
      path = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose-Notes.md"
      path.parent.mkdir(parents=True, exist_ok=True)
      path.write_text("# invalid\n", encoding="utf-8")
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must use lowercase kebab-case", result.stdout)

  def test_rejects_nested_governed_addon_path(self) -> None:
    with self.fixture_repo([("base", "bill-feature-implement")]) as repo_root:
      path = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose" / "review.md"
      path.parent.mkdir(parents=True, exist_ok=True)
      path.write_text("# invalid\n", encoding="utf-8")
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("expected add-on path format platform-packs/<package>/addons/<addon-file>.md", result.stdout)

  def test_rejects_runtime_playbook_references(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
      ],
      skill_contents={
        "bill-code-review": self.skill_with_runtime_playbook_reference("bill-code-review"),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must reference skill-local supporting files", result.stdout)

  def test_rejects_non_portable_review_orchestration_wording(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kmp", "bill-kmp-code-review"),
      ],
      skill_contents={
        "bill-kmp-code-review": self.portable_review_fixture_with_forbidden_wording(
          "bill-kmp-code-review"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must not hardcode the `task` tool", result.stdout)
      self.assertIn("must not describe review delegation as sub-agents", result.stdout)
      self.assertIn("must use portable routing-table wording", result.stdout)
      self.assertIn("must use portable summary wording", result.stdout)

  def test_rejects_portable_review_skill_without_review_run_id_contract(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kotlin", "bill-kotlin-code-review"),
      ],
      skill_contents={
        "bill-code-review": self.router_fixture_without_review_run_id(),
        "bill-kotlin-code-review": self.portable_review_fixture_without_review_run_id(
          "bill-kotlin-code-review"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("shared code-review router must expose", result.stdout)
      self.assertIn("portable review skills must expose", result.stdout)
      self.assertIn(REVIEW_SESSION_ID_PLACEHOLDER, result.stdout)
      self.assertIn("shared code-review router must define the review run id format", result.stdout)
      self.assertIn("shared code-review router must define the review session id format", result.stdout)

  def test_rejects_portable_review_skill_without_applied_learnings_contract(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kmp", "bill-kmp-code-review"),
      ],
      skill_contents={
        "bill-code-review": self.router_fixture_without_applied_learnings(),
        "bill-kmp-code-review": self.portable_review_fixture_without_applied_learnings(
          "bill-kmp-code-review"
        ),
      },
      review_orchestrator_has_applied_learnings=False,
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("shared code-review router must expose", result.stdout)
      self.assertIn("portable review skills must expose", result.stdout)
      self.assertIn("review orchestration contract must expose", result.stdout)

  def test_rejects_portable_review_skill_without_telemetry_sidecar(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kotlin", "bill-kotlin-code-review"),
      ],
      skill_contents={
        "bill-kotlin-code-review": self.portable_review_fixture_without_inline_lifecycle_handoff(
          "bill-kotlin-code-review"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must reference local supporting file 'telemetry-contract.md'", result.stdout)

  def test_rejects_review_orchestrator_without_machine_readable_finding_contract(self) -> None:
    with self.fixture_repo(
      [("base", "bill-code-review")],
      review_orchestrator_has_telemetry=False,
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("review orchestration contract must expose", result.stdout)
      self.assertIn(REVIEW_SESSION_ID_PLACEHOLDER, result.stdout)
      self.assertIn("review orchestration contract must define machine-readable findings", result.stdout)

  def test_rejects_review_orchestrator_without_heading_based_telemetry_sections(self) -> None:
    with self.fixture_repo(
      [("base", "bill-code-review")],
      review_orchestrator_uses_heading_sections=False,
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("review orchestration contract must define the telemetry ownership section as a markdown heading", result.stdout)
      self.assertIn("review orchestration contract must define the triage ownership section as a markdown heading", result.stdout)

  def test_accepts_orchestrator_skill_with_orchestrated_passthrough(self) -> None:
    with self.fixture_repo([("base", "bill-feature-implement")]) as repo_root:
      skill_md = repo_root / "skills" / "bill-feature-implement" / "SKILL.md"
      skill_md.write_text(
        skill_md.read_text(encoding="utf-8")
        + "\n\nWhen invoking child MCP tools, pass `orchestrated=true` to every call.\n",
        encoding="utf-8",
      )
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_rejects_orchestrator_skill_without_orchestrated_passthrough(self) -> None:
    with self.fixture_repo([("base", "bill-feature-implement")]) as repo_root:
      # Fixture skill uses the default boilerplate with no orchestration note.
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn(
        "orchestrator skill must instruct the agent to pass 'orchestrated=true'",
        result.stdout,
      )

  def test_accepts_telemeterable_skill_with_sidecar_reference(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kotlin", "bill-kotlin-code-review"),
      ],
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_rejects_telemeterable_skill_without_sidecar_reference(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kmp", "bill-kmp-code-review"),
      ],
      skill_contents={
        "bill-kmp-code-review": self.portable_review_fixture_without_telemetry_sidecar_reference(
          "bill-kmp-code-review"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must reference local supporting file 'telemetry-contract.md'", result.stdout)

  def test_rejects_telemeterable_skill_with_inline_contract_drift(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-code-review"),
        ("kotlin", "bill-kotlin-code-review"),
      ],
      skill_contents={
        "bill-kotlin-code-review": self.portable_review_fixture_with_inline_telemetry_drift(
          "bill-kotlin-code-review"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("must not contain inline telemetry contract text", result.stdout)

  def test_accepts_valid_platform_pack_in_fixture(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      self.write_platform_pack(
        repo_root,
        slug="fixture-pack",
        contract_version=SHELL_CONTRACT_VERSION,
        areas=["architecture"],
      )
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)
      self.assertIn("2 platform packs", result.stdout)

  def test_rejects_platform_pack_with_contract_version_mismatch(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      self.write_platform_pack(
        repo_root,
        slug="mismatch-pack",
        contract_version="9.99",
        areas=[],
      )
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("mismatch-pack", result.stdout)
      self.assertIn("contract_version", result.stdout)

  def test_rejects_platform_pack_missing_required_section(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      self.write_platform_pack(
        repo_root,
        slug="broken-pack",
        contract_version=SHELL_CONTRACT_VERSION,
        areas=[],
        skip_section="Descriptor",
      )
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("broken-pack", result.stdout)
      self.assertIn("Descriptor", result.stdout)

  def test_accepts_platform_pack_skills_not_listed_in_readme_catalog(self) -> None:
    with self.fixture_repo([("base", "bill-code-review")]) as repo_root:
      self.write_platform_pack(
        repo_root,
        slug="java",
        contract_version=SHELL_CONTRACT_VERSION,
        areas=[],
      )
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_pre_shell_platform_skills_not_listed_in_readme_catalog(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-feature-implement"),
        ("base", "bill-feature-verify"),
        ("java", "bill-java-feature-implement"),
        ("java", "bill-java-feature-verify"),
      ],
      skill_contents={
        "bill-feature-implement": (
          self.skill_markdown("bill-feature-implement")
          + "\nWhen invoking child MCP tools, pass `orchestrated=true` to every call.\n"
        ),
        "bill-feature-verify": (
          self.skill_markdown("bill-feature-verify")
          + "\nWhen invoking child MCP tools, pass `orchestrated=true` to every call.\n"
        ),
      },
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def write_platform_pack(
    self,
    repo_root: Path,
    *,
    slug: str,
    contract_version: str,
    areas: list[str],
    skip_section: str | None = None,
  ) -> None:
    """Write a platform pack under ``platform-packs/<slug>/``."""

    import yaml

    pack_dir = repo_root / "platform-packs" / slug
    pack_dir.mkdir(parents=True, exist_ok=True)

    baseline_name = f"bill-{slug}-code-review"
    baseline_rel = f"code-review/{baseline_name}/SKILL.md"
    baseline_dir = pack_dir / "code-review" / baseline_name
    baseline_path = baseline_dir / "SKILL.md"
    self.write_governed_skill(
      repo_root,
      skill_dir=baseline_dir,
      skill_name=baseline_name,
      platform=slug,
      display_name=slug.replace("-", " ").title(),
      area="",
      area_focus="",
      required_sidecars=required_supporting_files_for_skill(baseline_name),
      skip_heading=skip_section,
    )
    targets = supporting_file_targets(repo_root)
    for file_name in required_supporting_files_for_skill(baseline_name):
      supporting_path = baseline_path.parent / file_name
      if not supporting_path.exists():
        supporting_path.symlink_to(targets[file_name])

    declared_files: dict[str, object] = {
      "baseline": baseline_rel,
      "areas": {},
    }
    area_metadata: dict[str, dict[str, str]] = {}
    for area in areas:
      skill_name = f"bill-{slug}-code-review-{area}"
      area_rel = f"code-review/{skill_name}/SKILL.md"
      area_dir = pack_dir / "code-review" / skill_name
      area_focus = area.replace("-", " ")
      self.write_governed_skill(
        repo_root,
        skill_dir=area_dir,
        skill_name=skill_name,
        platform=slug,
        display_name=slug.replace("-", " ").title(),
        area=area,
        area_focus=area_focus,
        required_sidecars=required_supporting_files_for_skill(skill_name),
      )
      for file_name in required_supporting_files_for_skill(skill_name):
        supporting_path = area_dir / file_name
        if not supporting_path.exists():
          supporting_path.symlink_to(targets[file_name])
      declared_files["areas"][area] = area_rel
      area_metadata[area] = {"focus": area_focus}

    manifest = {
      "platform": slug,
      "contract_version": contract_version,
      "routing_signals": {
        "strong": [f".{slug}"],
        "tie_breakers": [],
      },
      "declared_code_review_areas": areas,
      "declared_files": declared_files,
      "area_metadata": area_metadata,
    }
    (pack_dir / "platform.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

  def write_governed_skill(
    self,
    repo_root: Path,
    *,
    skill_dir: Path,
    skill_name: str,
    platform: str,
    display_name: str,
    area: str,
    area_focus: str,
    required_sidecars: tuple[str, ...],
    skip_heading: str | None = None,
  ) -> None:
    skill_dir.mkdir(parents=True, exist_ok=True)
    descriptor = render_descriptor_section(
      ScaffoldTemplateContext(
        skill_name=skill_name,
        family="code-review",
        platform=platform,
        area=area,
        display_name=display_name,
      ),
      metadata=DescriptorMetadata(area_focus=area_focus),
    )
    shell_lines = [f"---\nname: {skill_name}\ndescription: Fixture platform pack content.\n---\n"]
    if skip_heading != "Descriptor":
      shell_lines.append(descriptor.rstrip())
    if skip_heading != "Execution":
      shell_lines.append("## Execution\n\nFollow the instructions in [content.md](content.md).\n")
    if skip_heading != "Ceremony":
      shell_lines.append(
        render_ceremony_section(
          ScaffoldTemplateContext(
            skill_name=skill_name,
            family="code-review",
            platform=platform,
            area=area,
            display_name=display_name,
          )
        ).rstrip()
      )
    (skill_dir / "SKILL.md").write_text("\n\n".join(shell_lines) + "\n", encoding="utf-8")
    content_sections = [
      ("Description", "Fixture description."),
      ("Specialist Scope", "Fixture specialist scope."),
      ("Inputs", "Fixture inputs."),
      ("Outputs Contract", "Fixture outputs contract."),
      ("Execution Mode Reporting", "Fixture execution mode reporting."),
      ("Telemetry Ceremony Hooks", "Fixture telemetry ceremony hooks."),
    ]
    content_lines = ["# Fixture Content", ""]
    if skill_name in {"bill-kotlin-code-review", "bill-kmp-code-review"}:
      content_lines.extend(
        [
          REVIEW_SESSION_ID_PLACEHOLDER,
          f"Use the review session id format {REVIEW_SESSION_ID_FORMAT}.",
          REVIEW_RUN_ID_PLACEHOLDER,
          f"Use the review run id format {REVIEW_RUN_ID_FORMAT}.",
          APPLIED_LEARNINGS_PLACEHOLDER,
          "",
        ]
      )
    for heading, body in content_sections:
      if skip_heading is not None and heading == skip_heading:
        continue
      content_lines.extend([f"## {heading}", body, ""])
    for file_name in required_sidecars:
      if file_name in ADDON_SUPPORTING_FILE_TARGETS:
        content_lines.append(f"[{file_name}]({file_name})")
    (skill_dir / "content.md").write_text("\n".join(content_lines) + "\n", encoding="utf-8")

  def run_validator(self, repo_root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
      [sys.executable, str(VALIDATOR_PATH), str(repo_root)],
      capture_output=True,
      text=True,
      check=False,
    )

  @contextmanager
  def fixture_repo(
    self,
    skills: list[tuple[str, str]],
    *,
    skill_contents: dict[str, str] | None = None,
    review_orchestrator_has_telemetry: bool = True,
    review_orchestrator_has_applied_learnings: bool = True,
    review_orchestrator_uses_heading_sections: bool = True,
  ):
    with tempfile.TemporaryDirectory() as temp_dir:
      repo_root = Path(temp_dir)
      self.write_readme(repo_root, [skill_name for _, skill_name in skills])
      self.write_skill_overrides_example(repo_root, skills[0][1])
      self.write_plugin_manifest(repo_root)
      self.write_stack_routing_playbook(repo_root)
      self.write_review_orchestrator_playbook(
        repo_root,
        include_telemetry=review_orchestrator_has_telemetry,
        include_applied_learnings=review_orchestrator_has_applied_learnings,
        use_heading_sections=review_orchestrator_uses_heading_sections,
      )
      self.write_review_specialist_contract_playbook(repo_root)
      self.write_review_delegation_playbook(repo_root)
      self.write_telemetry_contract_playbook(repo_root)
      self.write_shell_content_contract_playbook(repo_root)
      self.write_review_scope_playbook(repo_root)
      self.write_platform_pack(
        repo_root,
        slug="kmp",
        contract_version=SHELL_CONTRACT_VERSION,
        areas=[],
      )
      self.write_governed_addons(repo_root)

      for package_name, skill_name in skills:
        self.write_skill(
          repo_root,
          package_name,
          skill_name,
          content=(skill_contents or {}).get(skill_name),
        )
        self.write_supporting_files(repo_root, package_name, skill_name)

      yield repo_root

  def write_readme(self, repo_root: Path, skill_names: list[str]) -> None:
    rows = "\n".join(
      f"| `/{skill_name}` | Fixture skill used for validator e2e coverage. |"
      for skill_name in skill_names
    )
    readme = (
      f"# Fixture Repo\n\n"
      f"This fixture is a collection of {len(skill_names)} AI skills.\n\n"
      f"### Fixture Skills ({len(skill_names)} skills)\n\n"
      "| Slash command | Purpose |\n"
      "| --- | --- |\n"
      f"{rows}\n"
    )
    (repo_root / "README.md").write_text(readme, encoding="utf-8")

  def write_skill_overrides_example(self, repo_root: Path, skill_name: str) -> None:
    path = repo_root / ".agents" / "skill-overrides.example.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        f"""\
        # Skill Overrides

        ## {skill_name}
        - Fixture override entry used for validator e2e coverage.
        """
      ),
      encoding="utf-8",
    )

  def write_plugin_manifest(self, repo_root: Path) -> None:
    path = repo_root / ".claude-plugin" / "plugin.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      json.dumps(
        {
          "name": "fixture-skill-bill",
          "description": "Fixture plugin metadata for validator end-to-end coverage.",
          "keywords": ["fixture", "validator"],
        },
        indent=2,
      )
      + "\n",
      encoding="utf-8",
    )

  def write_stack_routing_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "stack-routing" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: stack-routing
        description: Single source of truth for shared stack routing behavior.
        ---

        # Shared Stack Routing Contract

        This is the canonical stack-routing contract. Skills consume it through sibling symlinks,
        so changes here propagate to every linked skill immediately.
        """
      ),
      encoding="utf-8",
    )

  def write_review_orchestrator_playbook(
    self,
    repo_root: Path,
    *,
    include_telemetry: bool = True,
    include_applied_learnings: bool = True,
    use_heading_sections: bool = True,
  ) -> None:
    path = repo_root / "orchestration" / "review-orchestrator" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    playbook = textwrap.dedent(
      """\
      ---
      name: review-orchestrator
      description: Single source of truth for shared review orchestration contracts.
      ---

      # Shared Code Review Orchestrator Contract

      This is the canonical review-orchestration contract. Skills consume it through sibling symlinks,
      so changes here propagate to every linked skill immediately.
      """
    )
    if include_telemetry:
      playbook = (
        playbook
        + f"\n{REVIEW_SESSION_ID_PLACEHOLDER}\n"
        + f"Use the review session id format {REVIEW_SESSION_ID_FORMAT}.\n"
        + f"\n{REVIEW_RUN_ID_PLACEHOLDER}\n"
        + f"Use the review run id format {REVIEW_RUN_ID_FORMAT}.\n"
    )
    if include_applied_learnings:
      playbook = playbook + f"{APPLIED_LEARNINGS_PLACEHOLDER}\n"
    if include_telemetry:
      telemetry_heading = f"## {TELEMETRY_OWNERSHIP_HEADING}" if use_heading_sections else TELEMETRY_OWNERSHIP_HEADING
      triage_heading = f"## {TRIAGE_OWNERSHIP_HEADING}" if use_heading_sections else TRIAGE_OWNERSHIP_HEADING
      playbook = (
        playbook
        + f"{RISK_REGISTER_FINDING_FORMAT}\n"
        + f"{telemetry_heading}\n"
        + f"{PARENT_IMPORT_RULE}\n"
        + f"{CHILD_NO_IMPORT_RULE}\n"
        + f"{CHILD_METADATA_HANDOFF_RULE}\n"
        + f"{triage_heading}\n"
        + f"{PARENT_TRIAGE_RULE}\n"
        + f"{CHILD_NO_TRIAGE_RULE}\n"
        + f"{NO_FINDINGS_TRIAGE_RULE}\n"
      )
    path.write_text(playbook, encoding="utf-8")

  def write_review_delegation_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "review-delegation" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: review-delegation
        description: Single source of truth for agent-specific delegated review execution.
        ---

        # Shared Review Delegation Contract

        This is the canonical review-delegation contract. Skills consume it through sibling symlinks,
        so changes here propagate to every linked skill immediately.

        ## GitHub Copilot CLI
        ## Claude Code
        ## OpenAI Codex
        ## GLM

        Every delegated worker must receive the current `review_session_id` and `review_run_id` when they already exist.
        """
      ),
      encoding="utf-8",
    )

  def write_review_scope_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "review-scope" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: review-scope
        description: Single source of truth for shared review scope selection.
        ---

        # Shared Review Scope Contract

        Supported review scopes:

        - Specific files (list paths)
        - Git commits (hashes/range)
        - Staged changes (`git diff --cached`; index only)
        - Unstaged changes (`git diff`; working tree only)
        - Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
        - Entire PR
        """
      ),
      encoding="utf-8",
    )

  def write_review_specialist_contract_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "review-orchestrator" / "specialist-contract.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: specialist-contract
        description: Fixture shared contract for delegated specialist review output.
        ---

        # Shared Review Specialist Contract

        Apply this shared contract to every specialist review output.

        ## Output Rules
        - Report at most 7 findings.
        - Include the user-visible or externally observable consequence for each finding.
        - Include `file:line` evidence for each finding.
        - Severity: `Blocker | Major | Minor`
        - Confidence: `High | Medium | Low`
        - Include a minimal, concrete fix.
        """
      ),
      encoding="utf-8",
    )

  def write_telemetry_contract_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "telemetry-contract" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        f"""\
        ---
        name: telemetry-contract
        description: Single source of truth for shared telemetry event semantics, orchestration flags, and learnings resolution.
        ---

        # Shared Telemetry Contract

        This is the canonical telemetry contract for skill-bill skills. Skills consume it through sibling symlinks,
        so changes here propagate to every linked skill immediately.

        ## Telemetry Ownership

        {PARENT_IMPORT_RULE}
        {CHILD_NO_IMPORT_RULE}
        {CHILD_METADATA_HANDOFF_RULE}

        ## Triage Ownership

        {PARENT_TRIAGE_RULE}
        {CHILD_NO_TRIAGE_RULE}
        {NO_FINDINGS_TRIAGE_RULE}
        """
      ),
      encoding="utf-8",
    )

  def write_shell_content_contract_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "shell-content-contract" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: shell-content-contract
        description: Fixture shell+content contract used for validator e2e coverage.
        ---

        # Shared Shell Content Contract

        Fixture contract playbook.
        """
      ),
      encoding="utf-8",
    )
    (path.parent / "shell-ceremony.md").write_text(
      textwrap.dedent(
        """\
        ## Project Overrides

        If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

        ## Inputs

        Fixture shell ceremony inputs.

        ## Execution Mode Reporting

        Execution mode: inline | delegated

        ## Telemetry Ceremony Hooks

        Follow `telemetry-contract.md` when it is present.
        """
      ),
      encoding="utf-8",
    )

  def write_governed_addons(self, repo_root: Path) -> None:
    implementation = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose-implementation.md"
    implementation.parent.mkdir(parents=True, exist_ok=True)
    implementation.write_text(
      textwrap.dedent(
        """\
        # KMP Android Compose Add-On

        This governed add-on provides implementation guidance for KMP Compose work.

        ## Section index
        - [android-compose-edge-to-edge.md](android-compose-edge-to-edge.md)
        - [android-navigation-implementation.md](android-navigation-implementation.md)
        """
      ),
      encoding="utf-8",
    )
    review = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose-review.md"
    review.write_text(
      textwrap.dedent(
        """\
        # KMP Android Compose Review Add-On

        This governed add-on provides review guidance for KMP Compose work.

        ## Section index
        - [android-navigation-review.md](android-navigation-review.md)
        - [android-r8-review.md](android-r8-review.md)
        """
      ),
      encoding="utf-8",
    )
    edge_to_edge = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose-edge-to-edge.md"
    edge_to_edge.write_text("# Edge-to-edge\n", encoding="utf-8")
    adaptive = repo_root / "platform-packs" / "kmp" / "addons" / "android-compose-adaptive-layouts.md"
    adaptive.write_text("# Adaptive layouts\n", encoding="utf-8")
    navigation_impl = repo_root / "platform-packs" / "kmp" / "addons" / "android-navigation-implementation.md"
    navigation_impl.write_text("# Navigation implementation\n", encoding="utf-8")
    navigation_review = repo_root / "platform-packs" / "kmp" / "addons" / "android-navigation-review.md"
    navigation_review.write_text("# Navigation review\n", encoding="utf-8")
    interop_impl = repo_root / "platform-packs" / "kmp" / "addons" / "android-interop-implementation.md"
    interop_impl.write_text("# Interop implementation\n", encoding="utf-8")
    interop_review = repo_root / "platform-packs" / "kmp" / "addons" / "android-interop-review.md"
    interop_review.write_text("# Interop review\n", encoding="utf-8")
    design_impl = repo_root / "platform-packs" / "kmp" / "addons" / "android-design-system-implementation.md"
    design_impl.write_text("# Design system implementation\n", encoding="utf-8")
    design_review = repo_root / "platform-packs" / "kmp" / "addons" / "android-design-system-review.md"
    design_review.write_text("# Design system review\n", encoding="utf-8")
    r8_implementation = repo_root / "platform-packs" / "kmp" / "addons" / "android-r8-implementation.md"
    r8_implementation.write_text("# R8 implementation\n", encoding="utf-8")
    r8_review = repo_root / "platform-packs" / "kmp" / "addons" / "android-r8-review.md"
    r8_review.write_text("# R8 review\n", encoding="utf-8")

  def write_skill(
    self,
    repo_root: Path,
    package_name: str,
    skill_name: str,
    *,
    content: str | None = None,
  ) -> None:
    if package_name == "base":
      path = repo_root / "skills" / skill_name / "SKILL.md"
    else:
      path = repo_root / "skills" / package_name / skill_name / "SKILL.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content or self.skill_markdown(skill_name), encoding="utf-8")

  def write_supporting_files(self, repo_root: Path, package_name: str, skill_name: str) -> None:
    targets = supporting_file_targets(repo_root)
    if package_name == "base":
      skill_dir = repo_root / "skills" / skill_name
    else:
      skill_dir = repo_root / "skills" / package_name / skill_name
    for file_name in required_supporting_files_for_skill(skill_name):
      (skill_dir / file_name).symlink_to(targets[file_name])

  def skill_markdown(self, skill_name: str) -> str:
    lines = [
      f"---",
      f"name: {skill_name}",
      f"description: Use when validating fixture taxonomy behavior for {skill_name}.",
      f"---",
      f"",
      f"# {skill_name}",
      f"",
      f"## Project Overrides",
      f"",
      f"Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).",
      f"",
      f"If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.",
      f"",
      f"Use this fixture skill for validator end-to-end coverage.",
    ]
    if skill_name == "bill-code-review" or "telemetry-contract.md" in required_supporting_files_for_skill(skill_name):
      lines.extend([
        REVIEW_SESSION_ID_PLACEHOLDER,
        f"Use the review session id format {REVIEW_SESSION_ID_FORMAT}.",
        REVIEW_RUN_ID_PLACEHOLDER,
        f"Use the review run id format {REVIEW_RUN_ID_FORMAT}.",
        APPLIED_LEARNINGS_PLACEHOLDER,
      ])
    for sidecar in required_supporting_files_for_skill(skill_name):
      lines.append(f"[{sidecar}]({sidecar})")
    return "\n".join(lines) + "\n"

  def skill_with_runtime_playbook_reference(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill used for validator portability coverage.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      Read `.bill-shared/orchestration/stack-routing/PLAYBOOK.md` before routing.
      """
    )

  def portable_review_fixture_with_forbidden_wording(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill used for validator portability coverage.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      For telemetry and triage rules, follow [telemetry-contract.md](telemetry-contract.md).

      | Signal | Agent to spawn |
      | --- | --- |
      | fixture | `bill-kotlin-code-review-security` |

      Spawn all selected sub-agents simultaneously using the `task` tool.

      Agents spawned: bill-kotlin-code-review-security
      """
    )

  def router_fixture_without_review_run_id(self) -> str:
    return textwrap.dedent(
      """\
      ---
      name: bill-code-review
      description: Fixture shared review router used for validator telemetry coverage.
      ---

      # bill-code-review

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-code-review` section, read that section and apply it as the highest-priority instruction for this skill.

      Shared router fixture without telemetry summary output.
      """
    )

  def portable_review_fixture_without_review_run_id(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill missing telemetry summary output.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      For telemetry and triage rules, follow [telemetry-contract.md](telemetry-contract.md).
      Specialist review fixture content.
      """
    )

  def router_fixture_without_applied_learnings(self) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: bill-code-review
      description: Fixture shared review router missing applied learnings output.
      ---

      # bill-code-review

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-code-review` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      Use the review session id format {REVIEW_SESSION_ID_FORMAT}.
      {REVIEW_RUN_ID_PLACEHOLDER}
      Use the review run id format {REVIEW_RUN_ID_FORMAT}.
      Shared router fixture without learnings summary output.
      """
    )

  def portable_review_fixture_without_applied_learnings(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill missing applied learnings summary output.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      For telemetry and triage rules, follow [telemetry-contract.md](telemetry-contract.md).
      Specialist review fixture content.
      """
    )

  def portable_review_fixture_without_inline_lifecycle_handoff(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill missing parent-owned telemetry handoff.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      Specialist review fixture content.
      """
    )

  def portable_review_fixture_with_plaintext_telemetry_sections(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill with plain-text telemetry labels instead of markdown headings.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      {TELEMETRY_OWNERSHIP_HEADING}

      {CHILD_NO_IMPORT_RULE}
      {CHILD_METADATA_HANDOFF_RULE}
      {PARENT_IMPORT_RULE}
      - `review_text`: the complete review output (Section 1 through Section 4)

      {TRIAGE_OWNERSHIP_HEADING}

      {CHILD_NO_TRIAGE_RULE} the parent review owns triage handoff and telemetry completion.
      {PARENT_TRIAGE_RULE}
      - `review_run_id`: the review run ID from the review output
      - `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`

      {NO_FINDINGS_TRIAGE_RULE}
      Specialist review fixture content.
      """
    )


  def portable_review_fixture_without_telemetry_sidecar_reference(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill missing the shared telemetry sidecar reference.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      [stack-routing.md](stack-routing.md)
      Specialist review fixture content.
      """
    )

  def portable_review_fixture_with_inline_telemetry_drift(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Fixture review skill that references the sidecar but also contains inline contract text.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      {REVIEW_SESSION_ID_PLACEHOLDER}
      {REVIEW_RUN_ID_PLACEHOLDER}
      {APPLIED_LEARNINGS_PLACEHOLDER}
      [specialist-contract.md](specialist-contract.md)
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      [stack-routing.md](stack-routing.md)
      [telemetry-contract.md](telemetry-contract.md)

      ## Standalone-first contract

      This is inline contract text that should be in the sidecar, not here.
      Specialist review fixture content.
      """
    )


if __name__ == "__main__":
  unittest.main()
