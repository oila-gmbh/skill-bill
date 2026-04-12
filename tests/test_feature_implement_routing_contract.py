from __future__ import annotations

from pathlib import Path
import re
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from skill_repo_contracts import (  # noqa: E402
  APPLIED_LEARNINGS_PLACEHOLDER,
  CHILD_METADATA_HANDOFF_RULE,
  CHILD_NO_IMPORT_RULE,
  CHILD_NO_TRIAGE_RULE,
  NO_FINDINGS_TRIAGE_RULE,
  PARENT_IMPORT_RULE,
  PARENT_TRIAGE_RULE,
  PORTABLE_REVIEW_SKILLS,
  REVIEW_DELEGATION_REQUIRED_SECTIONS,
  REVIEW_RUN_ID_FORMAT,
  REVIEW_RUN_ID_PLACEHOLDER,
  REVIEW_SESSION_ID_FORMAT,
  REVIEW_SESSION_ID_PLACEHOLDER,
  RISK_REGISTER_FINDING_FORMAT,
  RUNTIME_SUPPORTING_FILES,
  TELEMETRY_OWNERSHIP_HEADING,
  TRIAGE_OWNERSHIP_HEADING,
  supporting_file_targets,
  skills_requiring_supporting_file,
)


def read(relative_path: str) -> str:
  return (ROOT / relative_path).read_text(encoding="utf-8")


FEATURE_IMPLEMENT = read("skills/base/bill-feature-implement/SKILL.md") + "\n" + read("skills/base/bill-feature-implement/reference.md")
CODE_REVIEW = read("skills/base/bill-code-review/SKILL.md")
QUALITY_CHECK = read("skills/base/bill-quality-check/SKILL.md")
PR_DESCRIPTION = read("skills/base/bill-pr-description/SKILL.md")
AGENT_CONFIG_CODE_REVIEW = read("skills/agent-config/bill-agent-config-code-review/SKILL.md")
AGENT_CONFIG_QUALITY_CHECK = read("skills/agent-config/bill-agent-config-quality-check/SKILL.md")
KOTLIN_CODE_REVIEW = read("skills/kotlin/bill-kotlin-code-review/SKILL.md")
BACKEND_KOTLIN_CODE_REVIEW = read("skills/backend-kotlin/bill-backend-kotlin-code-review/SKILL.md")
KMP_CODE_REVIEW = read("skills/kmp/bill-kmp-code-review/SKILL.md")
GO_CODE_REVIEW = read("skills/go/bill-go-code-review/SKILL.md")
STACK_ROUTING_PLAYBOOK = read("orchestration/stack-routing/PLAYBOOK.md")
REVIEW_ORCHESTRATOR_PLAYBOOK = read("orchestration/review-orchestrator/PLAYBOOK.md")
REVIEW_DELEGATION_PLAYBOOK = read("orchestration/review-delegation/PLAYBOOK.md")
PORTABLE_REVIEW_SKILL_TEXTS = {
  "bill-agent-config-code-review": AGENT_CONFIG_CODE_REVIEW,
  "bill-kotlin-code-review": KOTLIN_CODE_REVIEW,
  "bill-backend-kotlin-code-review": BACKEND_KOTLIN_CODE_REVIEW,
  "bill-kmp-code-review": KMP_CODE_REVIEW,
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


def markdown_heading_pattern(heading: str) -> re.Pattern[str]:
  return re.compile(rf"^#{{2,6}} {re.escape(heading)}$", re.MULTILINE)


def extract_level_two_section(text: str, heading: str) -> str:
  match = re.search(
    rf"(?ms)^## {re.escape(heading)}\n.*?(?=^## |\Z)",
    text,
  )
  if not match:
    raise AssertionError(f"Missing level-two section '{heading}'")
  return match.group(0).strip()


def read_specialist_contract(skill_name: str) -> str:
  return (find_skill_dir(skill_name) / "specialist-contract.md").read_text(encoding="utf-8")


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
    self.assertIn("Supported scope labels are `staged changes`, `unstaged changes`, `working tree`, `commit range`, `PR diff`, and `files`", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("When the caller asks for staged changes, inspect only the staged/index diff", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_SESSION_ID_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_SESSION_ID_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_RUN_ID_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_RUN_ID_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(APPLIED_LEARNINGS_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Prefer more specific scopes in this order: `skill`, `repo`, `global`", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("reuse it instead of generating a new one", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(RISK_REGISTER_FINDING_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertRegex(REVIEW_ORCHESTRATOR_PLAYBOOK, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
    self.assertIn("The review layer that owns the final merged review output for the current review lifecycle owns review telemetry.", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(CHILD_NO_IMPORT_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(CHILD_METADATA_HANDOFF_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(PARENT_IMPORT_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertRegex(REVIEW_ORCHESTRATOR_PLAYBOOK, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
    self.assertIn(CHILD_NO_TRIAGE_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(PARENT_TRIAGE_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(NO_FINDINGS_TRIAGE_RULE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("The parent review owns only the delegated workers it launched itself.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Track delegated workers by the ids returned when they are launched.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("the current `review_session_id` and `review_run_id` when they already exist", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("any applicable active learnings when they are available", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Do not use `list_agents` to discover delegated workers during normal review execution.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Delegated workers must not call those telemetry tools themselves.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("return structured review output plus telemetry-relevant metadata to the parent", REVIEW_DELEGATION_PLAYBOOK)
    for section in REVIEW_DELEGATION_REQUIRED_SECTIONS:
      self.assertIn(section, REVIEW_DELEGATION_PLAYBOOK)

  def test_feature_implement_invokes_shared_review_and_validation_routers(self) -> None:
    self.assertIn("Run `bill-code-review`", FEATURE_IMPLEMENT)
    self.assertIn("Run `bill-quality-check`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-code-review`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-quality-check`", FEATURE_IMPLEMENT)

  def test_pr_description_prefers_repo_native_templates(self) -> None:
    self.assertIn("## Repo-Native PR Template Search (mandatory)", PR_DESCRIPTION)
    self.assertIn("`.github/pull_request_template.md`", PR_DESCRIPTION)
    self.assertIn("`.github/PULL_REQUEST_TEMPLATE.md`", PR_DESCRIPTION)
    self.assertIn("`pull_request_template.md`", PR_DESCRIPTION)
    self.assertIn("`PULL_REQUEST_TEMPLATE.md`", PR_DESCRIPTION)
    self.assertIn("`.github/pull_request_template/*.md`", PR_DESCRIPTION)
    self.assertIn("`.github/PULL_REQUEST_TEMPLATE/*.md`", PR_DESCRIPTION)
    self.assertIn("When multiple templates are found and there is no obvious default, ask the user which one to use.", PR_DESCRIPTION)
    self.assertIn("Only when NO repo-native template is found at any of the above locations, fall back to the built-in Skill Bill template in the section below.", PR_DESCRIPTION)
    self.assertIn("Always search for a repo-native PR template first", PR_DESCRIPTION)

  def test_kotlin_context_routes_to_kotlin_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `kotlin` signals dominate without meaningful `kmp` or `backend-kotlin` markers, delegate to `bill-kotlin-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `kotlin` signals dominate, delegate to the canonical `bill-kotlin-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )

  def test_agent_config_context_routes_to_agent_config_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `agent-config` signals dominate, delegate to `bill-agent-config-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `agent-config` signals dominate, delegate to the canonical `bill-agent-config-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn("[stack-routing.md](stack-routing.md)", AGENT_CONFIG_CODE_REVIEW)
    self.assertIn("[review-orchestrator.md](review-orchestrator.md)", AGENT_CONFIG_CODE_REVIEW)
    self.assertIn("[review-delegation.md](review-delegation.md)", AGENT_CONFIG_CODE_REVIEW)
    self.assertIn("Typical Commands In This Repo Type:", AGENT_CONFIG_QUALITY_CHECK)

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
      "Step 2: Run `bill-kotlin-code-review` as the baseline review",
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

  def test_router_uses_adaptive_execution_contract(self) -> None:
    self.assertIn(
      "Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>",
      CODE_REVIEW,
    )
    self.assertIn(REVIEW_SESSION_ID_PLACEHOLDER, CODE_REVIEW)
    self.assertIn(REVIEW_SESSION_ID_FORMAT, CODE_REVIEW)
    self.assertIn(REVIEW_RUN_ID_PLACEHOLDER, CODE_REVIEW)
    self.assertIn(REVIEW_RUN_ID_FORMAT, CODE_REVIEW)
    self.assertIn(APPLIED_LEARNINGS_PLACEHOLDER, CODE_REVIEW)
    self.assertIn("the applicable active learnings for the current repo and routed review skill when they are available", CODE_REVIEW)
    self.assertIn("Execution mode: inline | delegated", CODE_REVIEW)
    self.assertIn("the current `review_session_id` when one already exists", CODE_REVIEW)
    self.assertIn("the current `review_run_id` when one already exists", CODE_REVIEW)
    self.assertIn(
      "If the caller asks for staged changes, route and review only the staged diff",
      CODE_REVIEW,
    )
    self.assertIn(
      "If the routed skill selects `inline`, run it inline in the current thread instead of spawning an extra routed worker just for indirection",
      CODE_REVIEW,
    )
    self.assertIn(
      "If delegated review is required for the current scope and the runtime lacks a documented delegation path or cannot start the required worker(s), stop and report that delegated review is required for this scope but unavailable on the current runtime",
      CODE_REVIEW,
    )
    self.assertRegex(CODE_REVIEW, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
    self.assertIn(PARENT_IMPORT_RULE, CODE_REVIEW)
    self.assertIn(CHILD_NO_IMPORT_RULE, CODE_REVIEW)
    self.assertIn(CHILD_METADATA_HANDOFF_RULE, CODE_REVIEW)
    self.assertRegex(CODE_REVIEW, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
    self.assertIn(PARENT_TRIAGE_RULE, CODE_REVIEW)
    self.assertIn(CHILD_NO_TRIAGE_RULE, CODE_REVIEW)
    self.assertIn(NO_FINDINGS_TRIAGE_RULE, CODE_REVIEW)

  def test_stack_review_skills_define_adaptive_execution_modes(self) -> None:
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
        self.assertIn(
          "Staged changes (`git diff --cached`; index only)",
          skill_text,
        )
        self.assertIn(
          "Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff",
          skill_text,
        )
        self.assertIn(
          "Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>",
          skill_text,
        )
        self.assertIn(REVIEW_RUN_ID_PLACEHOLDER, skill_text)
        self.assertIn(APPLIED_LEARNINGS_PLACEHOLDER, skill_text)
        self.assertRegex(skill_text, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
        self.assertIn(PARENT_IMPORT_RULE, skill_text)
        self.assertIn(CHILD_NO_IMPORT_RULE, skill_text)
        self.assertIn(CHILD_METADATA_HANDOFF_RULE, skill_text)
        self.assertRegex(skill_text, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
        self.assertIn(PARENT_TRIAGE_RULE, skill_text)
        self.assertIn(CHILD_NO_TRIAGE_RULE, skill_text)
        self.assertIn(NO_FINDINGS_TRIAGE_RULE, skill_text)
        self.assertIn("Execution mode: inline | delegated", skill_text)
        self.assertIn("Use `inline` only", skill_text)
        self.assertIn("If execution mode is `delegated`", skill_text)
        self.assertIn(
          "delegated review is required for this scope but unavailable on the current runtime",
          skill_text,
        )
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

  def test_specialist_contracts_match_orchestrator_subset(self) -> None:
    expected = "\n\n".join(
      (
        extract_level_two_section(REVIEW_ORCHESTRATOR_PLAYBOOK, "Shared Contract For Every Specialist"),
        extract_level_two_section(REVIEW_ORCHESTRATOR_PLAYBOOK, "Shared Report Structure"),
      )
    )

    for skill_name in PORTABLE_REVIEW_SKILL_TEXTS:
      with self.subTest(skill=skill_name):
        specialist_text = read_specialist_contract(skill_name)
        actual = "\n\n".join(
          (
            extract_level_two_section(specialist_text, "Shared Contract For Every Specialist"),
            extract_level_two_section(specialist_text, "Shared Report Structure"),
          )
        )
        self.assertEqual(expected, actual)
        self.assertNotIn("## Shared Scope Contract", specialist_text)
        self.assertNotIn("## Shared Execution Mode Contract", specialist_text)
        self.assertNotIn("## Shared Learnings Context", specialist_text)
        self.assertNotIn("## Shared Delegation Contract", specialist_text)


if __name__ == "__main__":
  unittest.main()
