from __future__ import annotations

from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from skill_repo_contracts import (  # noqa: E402
  PORTABLE_REVIEW_SKILLS,
  REVIEW_DELEGATION_REQUIRED_SECTIONS,
  RUNTIME_SUPPORTING_FILES,
  supporting_file_targets,
  skills_requiring_supporting_file,
)


def read(relative_path: str) -> str:
  return (ROOT / relative_path).read_text(encoding="utf-8")


FEATURE_IMPLEMENT = read("skills/base/bill-feature-implement/SKILL.md")
CODE_REVIEW = read("skills/base/bill-code-review/SKILL.md")
QUALITY_CHECK = read("skills/base/bill-quality-check/SKILL.md")
KOTLIN_CODE_REVIEW = read("skills/kotlin/bill-kotlin-code-review/SKILL.md")
BACKEND_KOTLIN_CODE_REVIEW = read("skills/backend-kotlin/bill-backend-kotlin-code-review/SKILL.md")
KMP_CODE_REVIEW = read("skills/kmp/bill-kmp-code-review/SKILL.md")
PHP_CODE_REVIEW = read("skills/php/bill-php-code-review/SKILL.md")
GO_CODE_REVIEW = read("skills/go/bill-go-code-review/SKILL.md")
STACK_ROUTING_PLAYBOOK = read("orchestration/stack-routing/PLAYBOOK.md")
REVIEW_ORCHESTRATOR_PLAYBOOK = read("orchestration/review-orchestrator/PLAYBOOK.md")
REVIEW_DELEGATION_PLAYBOOK = read("orchestration/review-delegation/PLAYBOOK.md")
PORTABLE_REVIEW_SKILL_TEXTS = {
  "bill-kotlin-code-review": KOTLIN_CODE_REVIEW,
  "bill-backend-kotlin-code-review": BACKEND_KOTLIN_CODE_REVIEW,
  "bill-kmp-code-review": KMP_CODE_REVIEW,
  "bill-php-code-review": PHP_CODE_REVIEW,
  "bill-go-code-review": GO_CODE_REVIEW,
}


def find_skill_dir(skill_name: str) -> Path:
  matches = list((ROOT / "skills").rglob(f"{skill_name}/SKILL.md"))
  if len(matches) != 1:
    raise AssertionError(f"Expected exactly one SKILL.md for {skill_name}, found {len(matches)}")
  return matches[0].parent


def sidecar_paths(file_name: str) -> dict[str, Path]:
  return {
    skill_name: find_skill_dir(skill_name) / file_name
    for skill_name in skills_requiring_supporting_file(file_name)
  }


class FeatureImplementRoutingContractTest(unittest.TestCase):
  def test_shared_router_skills_reference_local_stack_routing_sidecars(self) -> None:
    self.assertIn("[stack-routing.md](stack-routing.md)", CODE_REVIEW)
    self.assertIn("[review-delegation.md](review-delegation.md)", CODE_REVIEW)
    self.assertIn("[stack-routing.md](stack-routing.md)", QUALITY_CHECK)
    self.assertNotIn(".bill-shared/orchestration/", CODE_REVIEW)
    self.assertNotIn(".bill-shared/orchestration/", QUALITY_CHECK)
    self.assertNotIn("orchestration/stack-routing/PLAYBOOK.md", CODE_REVIEW)
    self.assertNotIn("orchestration/stack-routing/PLAYBOOK.md", QUALITY_CHECK)

    for skill_name, sidecar_path in sidecar_paths("stack-routing.md").items():
      with self.subTest(skill=skill_name):
        self.assertTrue(sidecar_path.is_symlink())
        self.assertEqual(sidecar_path.resolve(), ROOT / "orchestration" / "stack-routing" / "PLAYBOOK.md")

  def test_reference_playbooks_remain_available_for_maintainers(self) -> None:
    self.assertIn("maintainer-facing reference snapshot", STACK_ROUTING_PLAYBOOK)
    self.assertIn("maintainer-facing reference snapshot", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("maintainer-facing reference snapshot", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("sibling supporting files", STACK_ROUTING_PLAYBOOK)
    self.assertIn("sibling supporting files", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("sibling supporting files", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", STACK_ROUTING_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", REVIEW_DELEGATION_PLAYBOOK)
    for section in REVIEW_DELEGATION_REQUIRED_SECTIONS:
      self.assertIn(section, REVIEW_DELEGATION_PLAYBOOK)

  def test_feature_implement_invokes_shared_review_and_validation_routers(self) -> None:
    self.assertIn("Run the `bill-code-review` skill", FEATURE_IMPLEMENT)
    self.assertIn("run `bill-quality-check`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-code-review`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-quality-check`", FEATURE_IMPLEMENT)

  def test_kotlin_context_routes_to_kotlin_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `kotlin` signals dominate without meaningful `kmp` or `backend-kotlin` markers, delegate to `bill-kotlin-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `kotlin` signals dominate, delegate to the canonical `bill-kotlin-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )

  def test_backend_kotlin_context_routes_to_backend_review_and_current_quality_check(self) -> None:
    self.assertIn(
      "- If `backend-kotlin` signals dominate, delegate to `bill-backend-kotlin-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `backend-kotlin` signals dominate, delegate to the canonical quality-check implementation for the `backend-kotlin` package when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Today, until separate `kmp` and `backend-kotlin` quality-check implementations exist, route `kmp`, `backend-kotlin`, and `kotlin` work to `bill-kotlin-quality-check`.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "### Step 1: Run `bill-kotlin-code-review` as the baseline review",
      BACKEND_KOTLIN_CODE_REVIEW,
    )

  def test_kmp_context_routes_to_kmp_review_and_current_quality_check(self) -> None:
    self.assertIn(
      "- If `kmp` signals dominate, delegate to `bill-kmp-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `kmp` signals dominate, delegate to the canonical quality-check implementation for the `kmp` package when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Today, until separate `kmp` and `backend-kotlin` quality-check implementations exist, route `kmp`, `backend-kotlin`, and `kotlin` work to `bill-kotlin-quality-check`.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Otherwise use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_php_context_routes_to_php_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `php` signals dominate, delegate to `bill-php-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `php` signals dominate, delegate to the canonical `bill-php-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "[stack-routing.md](stack-routing.md)",
      PHP_CODE_REVIEW,
    )

  def test_go_context_routes_to_go_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `go` signals dominate, delegate to `bill-go-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `go` signals dominate, delegate to the canonical `bill-go-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "[stack-routing.md](stack-routing.md)",
      GO_CODE_REVIEW,
    )

  def test_kmp_plus_backend_context_uses_backend_baseline_inside_kmp_review(self) -> None:
    self.assertIn(
      "- If backend/server files are also touched, choose `bill-backend-kotlin-code-review` as the baseline review layer so backend coverage is preserved before this skill adds mobile-specific specialists.",
      KMP_CODE_REVIEW,
    )
    self.assertIn(
      "- Use `bill-backend-kotlin-code-review` when backend/server files or markers are meaningfully in scope",
      KMP_CODE_REVIEW,
    )
    self.assertIn(
      "- Otherwise use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_kotlin_baseline_refuses_to_pretend_it_is_full_backend_or_kmp_review(self) -> None:
    self.assertIn(
      "- If strong Android/KMP markers are present and this skill is invoked standalone, clearly say that `bill-kmp-code-review` is required for full Android/KMP coverage.",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "- If backend/server signals clearly dominate and this skill is invoked standalone, delegate to `bill-backend-kotlin-code-review` and stop instead of pretending this baseline layer is the full backend review.",
      KOTLIN_CODE_REVIEW,
    )

  def test_stack_review_skills_require_delegated_subagent_execution(self) -> None:
    forbidden_phrases = (
      "`task`",
      "spawn_agent",
      "sub-agent",
      "sub-agents",
      "Agent to spawn",
      "Agents spawned",
    )

    for skill_name, skill_text in PORTABLE_REVIEW_SKILL_TEXTS.items():
      with self.subTest(skill=skill_name):
        self.assertIn("specialist review", skill_text)
        self.assertIn("[review-orchestrator.md](review-orchestrator.md)", skill_text)
        self.assertIn("[review-delegation.md](review-delegation.md)", skill_text)
        self.assertIn("delegated subagent", skill_text)
        self.assertIn("guaranteed delegated review execution is unavailable", skill_text)
        self.assertNotIn(".bill-shared/orchestration/", skill_text)
        self.assertNotIn("orchestration/stack-routing/PLAYBOOK.md", skill_text)
        self.assertNotIn("orchestration/review-orchestrator/PLAYBOOK.md", skill_text)
        self.assertNotIn("orchestration/review-delegation/PLAYBOOK.md", skill_text)
        for forbidden_phrase in forbidden_phrases:
          self.assertNotIn(forbidden_phrase, skill_text)

    for skill_name, sidecar_path in sidecar_paths("review-orchestrator.md").items():
      with self.subTest(skill=skill_name):
        self.assertTrue(sidecar_path.is_symlink())
        self.assertEqual(sidecar_path.resolve(), ROOT / "orchestration" / "review-orchestrator" / "PLAYBOOK.md")

    for skill_name, sidecar_path in sidecar_paths("review-delegation.md").items():
      with self.subTest(skill=skill_name):
        self.assertTrue(sidecar_path.is_symlink())
        self.assertEqual(sidecar_path.resolve(), ROOT / "orchestration" / "review-delegation" / "PLAYBOOK.md")


if __name__ == "__main__":
  unittest.main()
