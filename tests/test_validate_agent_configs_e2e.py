from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


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

  def run_validator(self, repo_root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
      ["python3", str(VALIDATOR_PATH), str(repo_root)],
      capture_output=True,
      text=True,
      check=False,
    )

  @contextmanager
  def fixture_repo(self, skills: list[tuple[str, str]]):
    with tempfile.TemporaryDirectory() as temp_dir:
      repo_root = Path(temp_dir)
      self.write_readme(repo_root, [skill_name for _, skill_name in skills])
      self.write_skill_overrides_example(repo_root, skills[0][1])
      self.write_plugin_manifest(repo_root)
      self.write_stack_routing_playbook(repo_root)

      for package_name, skill_name in skills:
        self.write_skill(repo_root, package_name, skill_name)

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
        description: Fixture routing playbook used for validator end-to-end coverage.
        ---

        # Fixture Stack Routing

        This fixture playbook exists so the validator can confirm orchestration files are present.
        """
      ),
      encoding="utf-8",
    )

  def write_skill(self, repo_root: Path, package_name: str, skill_name: str) -> None:
    path = repo_root / "skills" / package_name / skill_name / "SKILL.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(self.skill_markdown(skill_name), encoding="utf-8")

  def skill_markdown(self, skill_name: str) -> str:
    return textwrap.dedent(
      f"""\
      ---
      name: {skill_name}
      description: Use when validating fixture taxonomy behavior for {skill_name}.
      ---

      # {skill_name}

      ## Project Overrides

      If `.agents/skill-overrides.md` exists in the project root and contains a `## {skill_name}` section, read that section and apply it as the highest-priority instruction for this skill.

      Use this fixture skill for validator end-to-end coverage.
      """
    )


if __name__ == "__main__":
  unittest.main()
