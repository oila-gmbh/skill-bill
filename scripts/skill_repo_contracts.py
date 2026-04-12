from __future__ import annotations

from pathlib import Path


ORCHESTRATION_PLAYBOOKS: dict[str, str] = {
  "stack-routing": "orchestration/stack-routing/PLAYBOOK.md",
  "review-orchestrator": "orchestration/review-orchestrator/PLAYBOOK.md",
  "review-delegation": "orchestration/review-delegation/PLAYBOOK.md",
}

SUPPORTING_FILE_TARGETS: dict[str, str] = {
  "stack-routing.md": ORCHESTRATION_PLAYBOOKS["stack-routing"],
  "review-orchestrator.md": ORCHESTRATION_PLAYBOOKS["review-orchestrator"],
  "review-delegation.md": ORCHESTRATION_PLAYBOOKS["review-delegation"],
}

RUNTIME_SUPPORTING_FILES: dict[str, tuple[str, ...]] = {
  "bill-code-review": ("stack-routing.md", "review-delegation.md"),
  "bill-quality-check": ("stack-routing.md",),
  "bill-agent-config-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
  "bill-kotlin-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
  "bill-backend-kotlin-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
  "bill-kmp-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
  "bill-go-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
}

REVIEW_DELEGATION_REQUIRED_SECTIONS = (
  "## GitHub Copilot CLI",
  "## Claude Code",
  "## OpenAI Codex",
  "## GLM",
)

PORTABLE_REVIEW_SKILLS = (
  "bill-agent-config-code-review",
  "bill-kotlin-code-review",
  "bill-backend-kotlin-code-review",
  "bill-kmp-code-review",
  "bill-go-code-review",
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


def skills_requiring_supporting_file(file_name: str) -> tuple[str, ...]:
  return tuple(
    skill_name
    for skill_name, supporting_files in RUNTIME_SUPPORTING_FILES.items()
    if file_name in supporting_files
  )


def supporting_file_targets(root: Path) -> dict[str, Path]:
  return {
    file_name: root / relative_path
    for file_name, relative_path in SUPPORTING_FILE_TARGETS.items()
  }
