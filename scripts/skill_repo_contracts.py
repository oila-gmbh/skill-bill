from __future__ import annotations

from pathlib import Path


ORCHESTRATION_PLAYBOOKS: dict[str, str] = {
  "stack-routing": "orchestration/stack-routing/PLAYBOOK.md",
  "review-orchestrator": "orchestration/review-orchestrator/PLAYBOOK.md",
  "review-delegation": "orchestration/review-delegation/PLAYBOOK.md",
  "telemetry-contract": "orchestration/telemetry-contract/PLAYBOOK.md",
  "shell-content-contract": "orchestration/shell-content-contract/PLAYBOOK.md",
}

ADDON_DIRECTORY_NAME = "addons"
ADDON_IMPLEMENTATION_SUFFIX = "-implementation.md"
ADDON_REVIEW_SUFFIX = "-review.md"
ADDON_REPORTING_LINE = "Selected add-ons: none | <add-on slugs>"
# TODO(SKILL-14 follow-up): migrate GOVERNED_STACK_ADDONS to discovery from
# platform-packs/<slug>/platform.yaml (using the `governs_addons: true` flag
# and the declared `addon_signals`). The SKILL-14 pilot intentionally scopes
# add-on discovery out: AC9 (manifest-driven routing) covers **routing
# playbooks and the validator**, and `GOVERNED_STACK_ADDONS` is an internal
# implementation detail of add-on sidecar wiring. Promoting it to discovery
# is mechanical but touches every skill sidecar graph, which is bigger than
# this pilot. Tracking in SKILL-15.
GOVERNED_STACK_ADDONS: dict[str, tuple[str, ...]] = {
  "kmp": (
    "android-compose",
    "android-navigation",
    "android-interop",
    "android-design-system",
    "android-r8",
  ),
}
GOVERNED_ADDON_SUPPORT_FILES: dict[str, tuple[str, ...]] = {
  "kmp": (
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  ),
}

ADDON_SUPPORTING_FILE_TARGETS: dict[str, str] = {
  f"{addon_slug}{ADDON_IMPLEMENTATION_SUFFIX}": f"platform-packs/{stack}/addons/{addon_slug}{ADDON_IMPLEMENTATION_SUFFIX}"
  for stack, addon_slugs in GOVERNED_STACK_ADDONS.items()
  for addon_slug in addon_slugs
}
ADDON_SUPPORTING_FILE_TARGETS.update({
  f"{addon_slug}{ADDON_REVIEW_SUFFIX}": f"platform-packs/{stack}/addons/{addon_slug}{ADDON_REVIEW_SUFFIX}"
  for stack, addon_slugs in GOVERNED_STACK_ADDONS.items()
  for addon_slug in addon_slugs
})
ADDON_SUPPORTING_FILE_TARGETS.update({
  file_name: f"platform-packs/{stack}/addons/{file_name}"
  for stack, file_names in GOVERNED_ADDON_SUPPORT_FILES.items()
  for file_name in file_names
})

_CODE_REVIEW_RUNTIME_SUPPORTING_FILES: tuple[str, ...] = (
  "stack-routing.md",
  "review-orchestrator.md",
  "review-delegation.md",
  "telemetry-contract.md",
)

_QUALITY_CHECK_RUNTIME_SUPPORTING_FILES: tuple[str, ...] = (
  "stack-routing.md",
  "telemetry-contract.md",
)

SUPPORTING_FILE_TARGETS: dict[str, str] = {
  "stack-routing.md": ORCHESTRATION_PLAYBOOKS["stack-routing"],
  "review-orchestrator.md": ORCHESTRATION_PLAYBOOKS["review-orchestrator"],
  "review-delegation.md": ORCHESTRATION_PLAYBOOKS["review-delegation"],
  "telemetry-contract.md": ORCHESTRATION_PLAYBOOKS["telemetry-contract"],
  "shell-content-contract.md": ORCHESTRATION_PLAYBOOKS["shell-content-contract"],
  **ADDON_SUPPORTING_FILE_TARGETS,
}

RUNTIME_SUPPORTING_FILES: dict[str, tuple[str, ...]] = {
  "bill-code-review": (
    "stack-routing.md",
    "review-delegation.md",
    "telemetry-contract.md",
    "shell-content-contract.md",
  ),
  "bill-quality-check": ("stack-routing.md", "telemetry-contract.md"),
  "bill-kotlin-quality-check": ("stack-routing.md", "telemetry-contract.md"),
  "bill-kotlin-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md", "telemetry-contract.md"),
  "bill-kmp-code-review": (
    "stack-routing.md",
    "review-orchestrator.md",
    "review-delegation.md",
    "telemetry-contract.md",
    "android-compose-review.md",
    "android-navigation-review.md",
    "android-interop-review.md",
    "android-design-system-review.md",
    "android-r8-review.md",
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  ),
  "bill-kmp-code-review-ui": (
    "android-compose-review.md",
    "android-navigation-review.md",
    "android-interop-review.md",
    "android-design-system-review.md",
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  ),
  "bill-feature-implement": (
    "telemetry-contract.md",
    "android-compose-implementation.md",
    "android-navigation-implementation.md",
    "android-interop-implementation.md",
    "android-design-system-implementation.md",
    "android-r8-implementation.md",
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  ),
  "bill-feature-verify": ("telemetry-contract.md",),
  "bill-pr-description": ("telemetry-contract.md",),
}

REVIEW_DELEGATION_REQUIRED_SECTIONS = (
  "## GitHub Copilot CLI",
  "## Claude Code",
  "## OpenAI Codex",
  "## GLM",
)

PORTABLE_REVIEW_SKILLS = (
  "bill-kotlin-code-review",
  "bill-kmp-code-review",
)

REVIEW_RUN_ID_PLACEHOLDER = "Review run ID: <review-run-id>"
REVIEW_RUN_ID_FORMAT = "rvw-YYYYMMDD-HHMMSS-XXXX"
REVIEW_SESSION_ID_PLACEHOLDER = "Review session ID: <review-session-id>"
REVIEW_SESSION_ID_FORMAT = "rvs-<uuid4>"
APPLIED_LEARNINGS_PLACEHOLDER = "Applied learnings: none | <learning references>"
RISK_REGISTER_FINDING_FORMAT = "- [F-001] <Severity> | <Confidence> | <file:line> | <description>"
TELEMETRY_OWNERSHIP_HEADING = "Telemetry Ownership"
TRIAGE_OWNERSHIP_HEADING = "Triage Ownership"
PARENT_IMPORT_RULE = (
  "If this review owns the final merged review output for the current review lifecycle, call the "
  "`import_review` MCP tool:"
)
CHILD_NO_IMPORT_RULE = (
  "If this review is delegated or layered under another review, do not call `import_review`."
)
CHILD_METADATA_HANDOFF_RULE = (
  "Return the complete review output plus summary metadata (`review_session_id`, `review_run_id`, "
  "detected scope/stack, execution mode, specialist reviews) to the parent review instead."
)
PARENT_TRIAGE_RULE = (
  "If this review owns the final merged review output for the current review lifecycle and the user "
  "responds to findings, call the `triage_findings` MCP tool:"
)
CHILD_NO_TRIAGE_RULE = (
  "If this review is delegated or layered under another review, do not call `triage_findings`;"
)
NO_FINDINGS_TRIAGE_RULE = "Skip triage recording when the final parent-owned review produced no findings."


TELEMETERABLE_SKILLS: tuple[str, ...] = tuple(
  skill_name
  for skill_name, supporting_files in RUNTIME_SUPPORTING_FILES.items()
  if "telemetry-contract.md" in supporting_files
)

INLINE_TELEMETRY_CONTRACT_MARKERS: tuple[str, ...] = (
  "Standalone-first contract",
  "child_steps aggregation",
  "Graceful degradation",
  "Routers never emit",
)


def skills_requiring_supporting_file(file_name: str) -> tuple[str, ...]:
  return tuple(
    skill_name
    for skill_name in RUNTIME_SUPPORTING_FILES
    if file_name in required_supporting_files_for_skill(skill_name)
  )


def required_supporting_files_for_skill(skill_name: str) -> tuple[str, ...]:
  supporting_files = RUNTIME_SUPPORTING_FILES.get(skill_name)
  if supporting_files is not None:
    return supporting_files

  if skill_name.startswith("bill-") and skill_name.endswith("-code-review"):
    return _CODE_REVIEW_RUNTIME_SUPPORTING_FILES

  if skill_name.startswith("bill-") and skill_name.endswith("-quality-check"):
    return _QUALITY_CHECK_RUNTIME_SUPPORTING_FILES

  return ()


def governed_addon_slugs_for_stack(stack: str) -> tuple[str, ...]:
  return GOVERNED_STACK_ADDONS.get(stack, ())


def supporting_file_targets(root: Path) -> dict[str, Path]:
  return {
    file_name: root / relative_path
    for file_name, relative_path in SUPPORTING_FILE_TARGETS.items()
  }
