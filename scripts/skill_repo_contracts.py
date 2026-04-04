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
  "bill-php-code-review": ("stack-routing.md", "review-orchestrator.md", "review-delegation.md"),
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
  "bill-php-code-review",
  "bill-go-code-review",
)

REVIEW_RUN_ID_PLACEHOLDER = "Review run ID: <review-run-id>"
REVIEW_RUN_ID_FORMAT = "rvw-YYYYMMDD-HHMMSS"
REVIEW_SESSION_ID_PLACEHOLDER = "Review session ID: <review-session-id>"
REVIEW_SESSION_ID_FORMAT = "rvs-YYYYMMDD-HHMMSS"
APPLIED_LEARNINGS_PLACEHOLDER = "Applied learnings: none | <learning references>"
RISK_REGISTER_FINDING_FORMAT = "- [F-001] <Severity> | <Confidence> | <file:line> | <description>"


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
