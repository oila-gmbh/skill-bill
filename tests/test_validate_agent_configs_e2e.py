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
sys.path.insert(0, str(ROOT / "scripts"))

from skill_repo_contracts import (  # noqa: E402
  APPLIED_LEARNINGS_PLACEHOLDER,
  REVIEW_RUN_ID_FORMAT,
  REVIEW_RUN_ID_PLACEHOLDER,
  REVIEW_SESSION_ID_FORMAT,
  REVIEW_SESSION_ID_PLACEHOLDER,
  RISK_REGISTER_FINDING_FORMAT,
  RUNTIME_SUPPORTING_FILES,
  supporting_file_targets,
)


VALIDATOR_PATH = Path(__file__).resolve().parents[1] / "scripts" / "validate_agent_configs.py"


class ValidateAgentConfigsE2ETest(unittest.TestCase):
  maxDiff = None

  def test_accepts_platform_override_of_dynamic_base_capability(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("php", "bill-php-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_approved_platform_code_review_specialization(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("php", "bill-php-code-review-security"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 0, result.stdout)

  def test_accepts_go_platform_override_of_dynamic_base_capability(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("go", "bill-go-ship-it"),
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
        ("php", "bill-php-laravel-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn(
        "platform skill 'bill-php-laravel-ship-it' must either override an approved base skill",
        result.stdout,
      )

  def test_rejects_unapproved_code_review_area(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("php", "bill-php-code-review-laravel"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("code-review specialization 'laravel' is not approved", result.stdout)

  def test_rejects_go_platform_only_capability_name(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("go", "bill-go-gin-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn(
        "platform skill 'bill-go-gin-ship-it' must either override an approved base skill",
        result.stdout,
      )

  def test_rejects_unknown_platform_package(self) -> None:
    with self.fixture_repo(
      [
        ("base", "bill-ship-it"),
        ("laravel", "bill-laravel-ship-it"),
      ]
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("package 'laravel' is not allowed", result.stdout)

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
        ("php", "bill-php-code-review"),
      ],
      skill_contents={
        "bill-php-code-review": self.portable_review_fixture_with_forbidden_wording(
          "bill-php-code-review"
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
        ("php", "bill-php-code-review"),
      ],
      skill_contents={
        "bill-code-review": self.router_fixture_without_review_run_id(),
        "bill-php-code-review": self.portable_review_fixture_without_review_run_id(
          "bill-php-code-review"
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
        ("php", "bill-php-code-review"),
      ],
      skill_contents={
        "bill-code-review": self.router_fixture_without_applied_learnings(),
        "bill-php-code-review": self.portable_review_fixture_without_applied_learnings(
          "bill-php-code-review"
        ),
      },
      review_orchestrator_has_applied_learnings=False,
    ) as repo_root:
      result = self.run_validator(repo_root)
      self.assertEqual(result.returncode, 1, result.stdout)
      self.assertIn("shared code-review router must expose", result.stdout)
      self.assertIn("portable review skills must expose", result.stdout)
      self.assertIn("review orchestration contract must expose", result.stdout)

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

  def run_validator(self, repo_root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
      ["python3", str(VALIDATOR_PATH), str(repo_root)],
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
      )
      self.write_review_delegation_playbook(repo_root)

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
        description: Maintainer-facing reference snapshot for shared stack routing behavior.
        ---

        # Shared Stack Routing Snapshot

        This maintainer-facing reference snapshot exists to document the shared routing contract.
        It is not a runtime dependency for installed skills.
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
  ) -> None:
    path = repo_root / "orchestration" / "review-orchestrator" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    playbook = textwrap.dedent(
      """\
      ---
      name: review-orchestrator
      description: Maintainer-facing reference snapshot for shared review orchestration behavior.
      ---

      # Shared Review Orchestrator Snapshot

      This maintainer-facing reference snapshot exists to document the shared review contract.
      It is not a runtime dependency for installed skills.
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
      playbook = playbook + f"{RISK_REGISTER_FINDING_FORMAT}\n"
    path.write_text(playbook, encoding="utf-8")

  def write_review_delegation_playbook(self, repo_root: Path) -> None:
    path = repo_root / "orchestration" / "review-delegation" / "PLAYBOOK.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
      textwrap.dedent(
        """\
        ---
        name: review-delegation
        description: Maintainer-facing reference snapshot for agent-specific delegated review execution.
        ---

        # Shared Review Delegation Snapshot

        This maintainer-facing reference snapshot exists to document the shared delegation contract.
        Runtime-facing skills consume it through sibling supporting files.

        ## GitHub Copilot CLI
        ## Claude Code
        ## OpenAI Codex
        ## GLM

        Every delegated worker must receive the current `review_session_id` and `review_run_id` when they already exist.
        """
      ),
      encoding="utf-8",
    )

  def write_skill(
    self,
    repo_root: Path,
    package_name: str,
    skill_name: str,
    *,
    content: str | None = None,
  ) -> None:
    path = repo_root / "skills" / package_name / skill_name / "SKILL.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content or self.skill_markdown(skill_name), encoding="utf-8")

  def write_supporting_files(self, repo_root: Path, package_name: str, skill_name: str) -> None:
    targets = supporting_file_targets(repo_root)
    skill_dir = repo_root / "skills" / package_name / skill_name
    for file_name in RUNTIME_SUPPORTING_FILES.get(skill_name, ()):
      (skill_dir / file_name).symlink_to(targets[file_name])

  def skill_markdown(self, skill_name: str) -> str:
    review_run_line = ""
    if skill_name == "bill-code-review" or skill_name in RUNTIME_SUPPORTING_FILES:
      review_run_line = (
        f"\n{REVIEW_SESSION_ID_PLACEHOLDER}\n"
        + f"Use the review session id format {REVIEW_SESSION_ID_FORMAT}."
        + f"\n{REVIEW_RUN_ID_PLACEHOLDER}\n"
        + f"Use the review run id format {REVIEW_RUN_ID_FORMAT}."
        + f"\n{APPLIED_LEARNINGS_PLACEHOLDER}"
      )
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Use when validating fixture taxonomy behavior for {skill_name}.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      Use this fixture skill for validator end-to-end coverage.{review_run_line}
      """
    )

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
      | Signal | Agent to spawn |
      | --- | --- |
      | fixture | `bill-php-code-review-security` |

      Spawn all selected sub-agents simultaneously using the `task` tool.

      Agents spawned: bill-php-code-review-security
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

      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
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
      [review-orchestrator.md](review-orchestrator.md)
      [review-delegation.md](review-delegation.md)
      Specialist review fixture content.
      """
    )


if __name__ == "__main__":
  unittest.main()
