from __future__ import annotations

from pathlib import Path
import re
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from skill_repo_contracts import (  # noqa: E402
  ADDON_REPORTING_LINE,
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
  governed_addon_slugs_for_stack,
  supporting_file_targets,
  skills_requiring_supporting_file,
)


def read(relative_path: str) -> str:
  return (ROOT / relative_path).read_text(encoding="utf-8")


FEATURE_IMPLEMENT = read("skills/bill-feature-implement/SKILL.md") + "\n" + read("skills/bill-feature-implement/reference.md")
CODE_REVIEW = read("skills/bill-code-review/SKILL.md")
QUALITY_CHECK = read("skills/bill-quality-check/SKILL.md")
PR_DESCRIPTION = read("skills/bill-pr-description/SKILL.md")
KOTLIN_CODE_REVIEW = read("platform-packs/kotlin/code-review/bill-kotlin-code-review/SKILL.md")
KMP_CODE_REVIEW = read("platform-packs/kmp/code-review/bill-kmp-code-review/SKILL.md")
KMP_ANDROID_COMPOSE_EDGE_TO_EDGE = read("platform-packs/kmp/addons/android-compose-edge-to-edge.md")
KMP_ANDROID_COMPOSE_ADAPTIVE = read("platform-packs/kmp/addons/android-compose-adaptive-layouts.md")
KMP_ANDROID_COMPOSE_IMPLEMENTATION = read("platform-packs/kmp/addons/android-compose-implementation.md")
KMP_ANDROID_COMPOSE_REVIEW = read("platform-packs/kmp/addons/android-compose-review.md")
KMP_ANDROID_NAVIGATION_IMPLEMENTATION = read("platform-packs/kmp/addons/android-navigation-implementation.md")
KMP_ANDROID_NAVIGATION_REVIEW = read("platform-packs/kmp/addons/android-navigation-review.md")
KMP_ANDROID_INTEROP_IMPLEMENTATION = read("platform-packs/kmp/addons/android-interop-implementation.md")
KMP_ANDROID_INTEROP_REVIEW = read("platform-packs/kmp/addons/android-interop-review.md")
KMP_ANDROID_DESIGN_SYSTEM_IMPLEMENTATION = read("platform-packs/kmp/addons/android-design-system-implementation.md")
KMP_ANDROID_DESIGN_SYSTEM_REVIEW = read("platform-packs/kmp/addons/android-design-system-review.md")
KMP_ANDROID_R8_IMPLEMENTATION = read("platform-packs/kmp/addons/android-r8-implementation.md")
KMP_ANDROID_R8_REVIEW = read("platform-packs/kmp/addons/android-r8-review.md")
KMP_COMPOSE_UI_REVIEW = read("platform-packs/kmp/code-review/bill-kmp-code-review-ui/SKILL.md")
STACK_ROUTING_PLAYBOOK = read("orchestration/stack-routing/PLAYBOOK.md")
REVIEW_ORCHESTRATOR_PLAYBOOK = read("orchestration/review-orchestrator/PLAYBOOK.md")
REVIEW_DELEGATION_PLAYBOOK = read("orchestration/review-delegation/PLAYBOOK.md")
TELEMETRY_CONTRACT_PLAYBOOK = read("orchestration/telemetry-contract/PLAYBOOK.md")
PORTABLE_REVIEW_SKILL_TEXTS = {
  "bill-kotlin-code-review": KOTLIN_CODE_REVIEW,
  "bill-kmp-code-review": KMP_CODE_REVIEW,
}


def find_skill_dir(skill_name: str) -> Path:
  matches = list((ROOT / "skills").rglob(f"{skill_name}/SKILL.md"))
  matches.extend((ROOT / "platform-packs").rglob(f"{skill_name}/SKILL.md"))
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
  def test_portable_review_skill_inventory_is_built_in_only(self) -> None:
    self.assertEqual(
      PORTABLE_REVIEW_SKILLS,
      ("bill-kotlin-code-review", "bill-kmp-code-review"),
    )

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
    self.assertIn("canonical", STACK_ROUTING_PLAYBOOK)
    self.assertIn("canonical", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("canonical", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("sibling symlinks", STACK_ROUTING_PLAYBOOK)
    self.assertIn("sibling symlinks", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("sibling symlinks", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", STACK_ROUTING_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Do not reference this repo-relative path directly", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Supported scope labels are `staged changes`, `unstaged changes`, `working tree`, `commit range`, `PR diff`, and `files`", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("When the caller asks for staged changes, inspect only the staged/index diff", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(ADDON_REPORTING_LINE, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_SESSION_ID_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_SESSION_ID_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_RUN_ID_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(REVIEW_RUN_ID_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(APPLIED_LEARNINGS_PLACEHOLDER, REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("Prefer more specific scopes in this order: `skill`, `repo`, `global`", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn("reuse it instead of generating a new one", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertIn(RISK_REGISTER_FINDING_FORMAT, REVIEW_ORCHESTRATOR_PLAYBOOK)
    # Telemetry Ownership and Triage Ownership headings remain in review-orchestrator
    # but the body now points to the canonical telemetry-contract playbook.
    self.assertRegex(REVIEW_ORCHESTRATOR_PLAYBOOK, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
    self.assertIn("../telemetry-contract/PLAYBOOK.md", REVIEW_ORCHESTRATOR_PLAYBOOK)
    self.assertRegex(REVIEW_ORCHESTRATOR_PLAYBOOK, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
    # The full rule strings live in the telemetry-contract playbook.
    self.assertIn("The review layer that owns the final merged review output for the current review lifecycle owns review telemetry.", TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_NO_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_METADATA_HANDOFF_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(PARENT_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_NO_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(PARENT_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(NO_FINDINGS_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn("## Governed add-ons", TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn("The parent review owns only the delegated workers it launched itself.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Track delegated workers by the ids returned when they are launched.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("the current `review_session_id` and `review_run_id` when they already exist", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("any applicable active learnings when they are available", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("any already-selected governed add-ons", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Do not use `list_agents` to discover delegated workers during normal review execution.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("Delegated workers must not call those telemetry tools themselves.", REVIEW_DELEGATION_PLAYBOOK)
    self.assertIn("return structured review output plus telemetry-relevant metadata to the parent", REVIEW_DELEGATION_PLAYBOOK)
    for section in REVIEW_DELEGATION_REQUIRED_SECTIONS:
      self.assertIn(section, REVIEW_DELEGATION_PLAYBOOK)

  def test_feature_implement_invokes_shared_review_and_validation_routers(self) -> None:
    self.assertIn("bill-code-review", FEATURE_IMPLEMENT)
    self.assertIn("bill-quality-check", FEATURE_IMPLEMENT)
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
    # SKILL-14 made `bill-code-review` manifest-driven. SKILL-16 made
    # `bill-quality-check` manifest-driven too via the optional
    # `declared_quality_check_file` key on each pack's manifest.
    self.assertIn("Generate a `routed_skill` value of `bill-<slug>-code-review`", CODE_REVIEW)
    self.assertIn("manifest-driven", QUALITY_CHECK)
    self.assertIn("declared_quality_check_file", QUALITY_CHECK)
    self.assertIn("load_quality_check_content", QUALITY_CHECK)

  def test_kmp_context_routes_to_kmp_review_and_current_quality_check(self) -> None:
    # Shell is manifest-driven (SKILL-14 + SKILL-16). kmp omits the
    # declared_quality_check_file manifest key and falls back to kotlin.
    self.assertIn("manifest-driven", CODE_REVIEW)
    self.assertIn("manifest-driven", QUALITY_CHECK)
    self.assertIn(
      "dominant pack is `kmp`, route\n  quality-check work to the `kotlin` pack",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_kmp_governed_addons_apply_only_after_stack_routing(self) -> None:
    # SKILL-14 leaves GOVERNED_STACK_ADDONS hardcoded with a TODO (see
    # scripts/skill_repo_contracts.py). Discovery of add-on governance is
    # scheduled for SKILL-15. The rest of the add-on contract moved to the
    # discovery-driven stack-routing playbook.
    self.assertEqual(
      governed_addon_slugs_for_stack("kmp"),
      ("android-compose", "android-navigation", "android-interop", "android-design-system", "android-r8"),
    )
    self.assertIn("## Post-Stack Add-Ons", STACK_ROUTING_PLAYBOOK)
    self.assertIn("Resolve governed add-ons only after the dominant stack route is chosen.", STACK_ROUTING_PLAYBOOK)
    self.assertIn("Selected add-ons: none", STACK_ROUTING_PLAYBOOK)

  def test_kmp_feature_implement_defers_governed_addons_to_stack_routing(self) -> None:
    self.assertIn(
      "When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`.",
      FEATURE_IMPLEMENT,
    )
    self.assertIn(
      "Let the routed pack own add-on detection and selection",
      FEATURE_IMPLEMENT,
    )
    self.assertIn(
      "scan the matching pack-owned add-on supporting files' `## Section index` headings first",
      FEATURE_IMPLEMENT,
    )
    self.assertIn(
      "If the add-on is split into topic files, open only the linked topic files whose cues match the work",
      FEATURE_IMPLEMENT,
    )
    self.assertNotIn(
      "android-compose-implementation.md",
      FEATURE_IMPLEMENT,
    )
    self.assertIn(
      '"selected_addons": ["<addon-slug>", ...]',
      FEATURE_IMPLEMENT,
    )

  def test_kmp_compose_review_skill_keeps_review_rubric_as_enforcement_layer(self) -> None:
    self.assertIn(
      "When the parent KMP review selects the `android-compose` add-on, scan [android-compose-review.md](android-compose-review.md) first. If the add-on is split into topic files, open only the linked topic files whose cues match the diff, such as [android-compose-edge-to-edge.md](android-compose-edge-to-edge.md) and [android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md).",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "When the parent KMP review selects `android-navigation`, scan [android-navigation-review.md](android-navigation-review.md) first",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "When the parent KMP review selects `android-interop`, scan [android-interop-review.md](android-interop-review.md) first",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "When the parent KMP review selects `android-design-system`, scan [android-design-system-review.md](android-design-system-review.md) first",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "For review enforcement, read [compose-guidelines.md](compose-guidelines.md) as the Compose review rubric",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "do not treat it as a standalone review command.",
      KMP_COMPOSE_UI_REVIEW,
    )
    self.assertIn(
      "This file is a review index for `bill-kmp-code-review` and `bill-kmp-code-review-ui`.",
      KMP_ANDROID_COMPOSE_REVIEW,
    )
    self.assertIn(
      "## Section index",
      KMP_ANDROID_COMPOSE_REVIEW,
    )
    self.assertIn(
      "[android-compose-edge-to-edge.md](android-compose-edge-to-edge.md)",
      KMP_ANDROID_COMPOSE_REVIEW,
    )
    self.assertIn(
      "[android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md)",
      KMP_ANDROID_COMPOSE_REVIEW,
    )
    self.assertIn("[android-navigation-review.md](android-navigation-review.md)", KMP_ANDROID_COMPOSE_REVIEW)
    self.assertIn("[android-design-system-review.md](android-design-system-review.md)", KMP_ANDROID_COMPOSE_REVIEW)
    self.assertIn("[android-interop-review.md](android-interop-review.md)", KMP_ANDROID_COMPOSE_REVIEW)
    self.assertIn(
      "- Keep this add-on subordinate to the routed `kmp` review.",
      KMP_ANDROID_COMPOSE_REVIEW,
    )
    for skill_name, sidecar_path in sidecar_paths("android-compose-review.md").items():
      with self.subTest(skill=skill_name):
        self.assertTrue(sidecar_path.is_symlink())
        self.assertEqual(sidecar_path.resolve(), ROOT / "platform-packs" / "kmp" / "addons" / "android-compose-review.md")
    self.assertIn(
      "Selected add-ons: none | <add-on slugs>",
      KMP_CODE_REVIEW,
    )

  def test_kmp_android_compose_implementation_addon_is_sectioned_for_selective_reads(self) -> None:
    self.assertIn("## Section index", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn(
      "open only the linked topic files whose cues match the current work instead of loading all Android guidance by default.",
      KMP_ANDROID_COMPOSE_IMPLEMENTATION,
    )
    self.assertIn("[android-compose-edge-to-edge.md](android-compose-edge-to-edge.md)", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn("[android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md)", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn("[android-navigation-implementation.md](android-navigation-implementation.md)", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn("[android-design-system-implementation.md](android-design-system-implementation.md)", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn("[android-interop-implementation.md](android-interop-implementation.md)", KMP_ANDROID_COMPOSE_IMPLEMENTATION)
    self.assertIn("## Android-specific verification checklist", KMP_ANDROID_COMPOSE_IMPLEMENTATION)

  def test_kmp_android_compose_topic_files_add_android_specific_depth_without_generic_compose_duplication(self) -> None:
    self.assertIn("system/edge-to-edge", KMP_ANDROID_COMPOSE_EDGE_TO_EDGE)
    self.assertIn("## Source recipes", KMP_ANDROID_COMPOSE_EDGE_TO_EDGE)
    self.assertIn("adjustResize", KMP_ANDROID_COMPOSE_EDGE_TO_EDGE)
    self.assertIn("## Source recipes", KMP_ANDROID_COMPOSE_ADAPTIVE)
    self.assertIn("list-detail", KMP_ANDROID_COMPOSE_ADAPTIVE)
    self.assertIn("material-listdetail", KMP_ANDROID_COMPOSE_ADAPTIVE)
    self.assertIn("NavigationSuiteScaffold", KMP_ANDROID_COMPOSE_ADAPTIVE)
    self.assertIn("system-bar legibility", KMP_ANDROID_COMPOSE_EDGE_TO_EDGE)

  def test_kmp_android_navigation_addon_captures_android_navigation_depth(self) -> None:
    self.assertIn("common-ui", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("multiple-backstacks", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("bottomsheet", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("modular-hilt", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("results-event", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("synthetic back stack", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("back stack per top-level route", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("typed destination models", KMP_ANDROID_NAVIGATION_IMPLEMENTATION)
    self.assertIn("Android navigation risks", KMP_ANDROID_NAVIGATION_REVIEW)

  def test_kmp_android_interop_and_design_system_addons_capture_android_ui_host_depth(self) -> None:
    self.assertIn("AndroidView", KMP_ANDROID_INTEROP_IMPLEMENTATION)
    self.assertIn("AndroidFragment", KMP_ANDROID_INTEROP_IMPLEMENTATION)
    self.assertIn("rememberUpdatedState", KMP_ANDROID_INTEROP_IMPLEMENTATION)
    self.assertIn("Interop", KMP_ANDROID_INTEROP_REVIEW)
    self.assertIn("MaterialTheme", KMP_ANDROID_DESIGN_SYSTEM_IMPLEMENTATION)
    self.assertIn("design-system", KMP_ANDROID_DESIGN_SYSTEM_IMPLEMENTATION)
    self.assertIn("hybrid XML/Compose", KMP_ANDROID_DESIGN_SYSTEM_IMPLEMENTATION)
    self.assertIn("duplicate or parallel Android theme layers", KMP_ANDROID_DESIGN_SYSTEM_REVIEW)

  def test_kmp_android_r8_addons_capture_android_shrinker_guidance(self) -> None:
    self.assertIn("## Section index", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("proguard-rules.pro", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("proguard-android-optimize.txt", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("-dontshrink", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("Class.forName", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("android-r8", KMP_ANDROID_R8_IMPLEMENTATION)
    self.assertIn("proguard-rules.pro", KMP_ANDROID_R8_REVIEW)
    self.assertIn("consumer rules already cover them", KMP_ANDROID_R8_REVIEW)
    self.assertIn("Parcelable", KMP_ANDROID_R8_REVIEW)
    self.assertIn("Android shrinker risks", KMP_ANDROID_R8_REVIEW)

  def test_kmp_mixed_backend_context_still_uses_kotlin_baseline_inside_kmp_review(self) -> None:
    self.assertIn(
      "- If backend/server files are also touched, keep the `kmp` route and use `bill-kotlin-code-review` as the baseline layer so shared Kotlin concerns are still reviewed before this skill adds mobile-specific specialists.",
      KMP_CODE_REVIEW,
    )
    self.assertIn(
      "- Use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_kotlin_baseline_handles_backend_scope_without_a_separate_pack(self) -> None:
    self.assertIn(
      "- If strong Android/KMP markers are present and this skill is invoked standalone, clearly say that `bill-kmp-code-review` is required for full Android/KMP coverage.",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "- Backend/server markers stay on the `kotlin` route. Select backend-focused Kotlin specialists for API contracts, persistence, and reliability when backend/server signals are present.",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "`bill-kotlin-code-review-api-contracts`",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "`bill-kotlin-code-review-persistence`",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "`bill-kotlin-code-review-reliability`",
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
      "If the routed pack selects `inline`, run it inline in the current thread instead of spawning an extra routed worker just for indirection",
      CODE_REVIEW,
    )
    self.assertIn(
      "If delegated review is required for the current scope and the runtime lacks a documented delegation path or cannot start the required worker(s), stop and report that delegated review is required for this scope but unavailable on the current runtime",
      CODE_REVIEW,
    )
    # Telemetry ownership rules now live in the telemetry-contract sidecar, not inline.
    self.assertIn("[telemetry-contract.md](telemetry-contract.md)", CODE_REVIEW)
    self.assertRegex(TELEMETRY_CONTRACT_PLAYBOOK, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
    self.assertIn(PARENT_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_NO_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_METADATA_HANDOFF_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertRegex(TELEMETRY_CONTRACT_PLAYBOOK, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
    self.assertIn(PARENT_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(CHILD_NO_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
    self.assertIn(NO_FINDINGS_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)

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
        # Telemetry ownership rules now live in the telemetry-contract sidecar, not inline.
        self.assertIn("[telemetry-contract.md](telemetry-contract.md)", skill_text)
        self.assertRegex(TELEMETRY_CONTRACT_PLAYBOOK, markdown_heading_pattern(TELEMETRY_OWNERSHIP_HEADING))
        self.assertIn(PARENT_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
        self.assertIn(CHILD_NO_IMPORT_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
        self.assertIn(CHILD_METADATA_HANDOFF_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
        self.assertRegex(TELEMETRY_CONTRACT_PLAYBOOK, markdown_heading_pattern(TRIAGE_OWNERSHIP_HEADING))
        self.assertIn(PARENT_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
        self.assertIn(CHILD_NO_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
        self.assertIn(NO_FINDINGS_TRIAGE_RULE, TELEMETRY_CONTRACT_PLAYBOOK)
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
