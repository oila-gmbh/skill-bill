from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re


DEFAULT_DB_PATH = Path.home() / ".skill-bill" / "review-metrics.db"
DEFAULT_CONFIG_PATH = Path.home() / ".skill-bill" / "config.json"
DB_ENVIRONMENT_KEY = "SKILL_BILL_REVIEW_DB"
CONFIG_ENVIRONMENT_KEY = "SKILL_BILL_CONFIG_PATH"
TELEMETRY_ENABLED_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_ENABLED"
TELEMETRY_LEVEL_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_LEVEL"
TELEMETRY_PROXY_URL_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_PROXY_URL"
TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN"
INSTALL_ID_ENVIRONMENT_KEY = "SKILL_BILL_INSTALL_ID"
TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_BATCH_SIZE"
TELEMETRY_LEVELS = ("off", "anonymous", "full")
DEFAULT_TELEMETRY_PROXY_URL = "https://skill-bill-telemetry-proxy.skillbill.workers.dev"
DEFAULT_TELEMETRY_BATCH_SIZE = 50
TELEMETRY_PROXY_CONTRACT_VERSION = "1"
FINDING_OUTCOME_TYPES = (
  "finding_accepted",
  "fix_applied",
  "finding_edited",
  "fix_rejected",
  "false_positive",
)
ACCEPTED_FINDING_OUTCOME_TYPES = (
  "finding_accepted",
  "fix_applied",
  "finding_edited",
)
REJECTED_FINDING_OUTCOME_TYPES = ("fix_rejected", "false_positive")
LEARNING_SCOPES = ("global", "repo", "skill")
LEARNING_STATUSES = ("active", "disabled")
LEARNING_SCOPE_PRECEDENCE = ("skill", "repo", "global")

EVENT_FEATURE_IMPLEMENT_STARTED = "skillbill_feature_implement_started"
EVENT_FEATURE_IMPLEMENT_FINISHED = "skillbill_feature_implement_finished"
EVENT_QUALITY_CHECK_STARTED = "skillbill_quality_check_started"
EVENT_QUALITY_CHECK_FINISHED = "skillbill_quality_check_finished"
EVENT_FEATURE_VERIFY_STARTED = "skillbill_feature_verify_started"
EVENT_FEATURE_VERIFY_FINISHED = "skillbill_feature_verify_finished"
EVENT_PR_DESCRIPTION_GENERATED = "skillbill_pr_description_generated"
EVENT_NEW_SKILL_SCAFFOLD_STARTED = "skillbill_new_skill_scaffold_started"
EVENT_NEW_SKILL_SCAFFOLD_FINISHED = "skillbill_new_skill_scaffold_finished"
REMOTE_STATS_WORKFLOWS = ("bill-feature-implement", "bill-feature-verify")

FEATURE_IMPLEMENT_SESSION_PREFIX = "fis"
FEATURE_IMPLEMENT_WORKFLOW_PREFIX = "wfl"
QUALITY_CHECK_SESSION_PREFIX = "qck"
FEATURE_VERIFY_SESSION_PREFIX = "fvr"
PR_DESCRIPTION_SESSION_PREFIX = "prd"
NEW_SKILL_SCAFFOLD_SESSION_PREFIX = "nss"

# Shell+content contract version. SKILL-21 moved this authority from
# skill_bill/shell_content_contract.py so the constant is the single source of
# truth for CLI, loader, scaffolder, and migration script. Bump in lockstep
# across the shell, the packs, and the migration tooling — v1.0 packs must
# loud-fail via ContractVersionMismatchError with a migration-script hint.
SHELL_CONTRACT_VERSION: str = "1.1"

# Scaffolder template version (SKILL-21). Bumped whenever the rendered SKILL.md
# shape or body changes so existing skills surface drift through
# ``skill-bill doctor`` and can be regenerated with ``skill-bill upgrade``.
# Template drift is not a runtime failure — it is an upgrade-actionable state.
TEMPLATE_VERSION: str = "2026.04.19.5"

# New-skill scaffolder constants (SKILL-15).
SCAFFOLD_PAYLOAD_VERSION: str = "1.0"
# Pre-shell capability families. These families have not been piloted onto the
# shell+content contract yet; the scaffolder places their platform overrides
# under the historic skills/<platform>/bill-<platform>-<capability>/ layout
# and annotates them with a migration-awareness note. SKILL-16 piloted
# ``quality-check`` onto the shell+content contract, so only
# ``feature-implement`` and ``feature-verify`` remain pre-shell.
PRE_SHELL_FAMILIES: tuple[str, ...] = (
  "feature-implement",
  "feature-verify",
)

QUALITY_CHECK_RESULTS = ("pass", "fail", "skipped", "unsupported_stack")
QUALITY_CHECK_SCOPE_TYPES = ("files", "working_tree", "branch_diff", "repo")
FEATURE_VERIFY_COMPLETION_STATUSES = (
  "completed",
  "abandoned_at_review",
  "abandoned_at_audit",
  "error",
)
FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION = "0.1"
FEATURE_VERIFY_WORKFLOW_PREFIX = "wfv"
FEATURE_VERIFY_WORKFLOW_STATUSES = (
  "pending",
  "running",
  "completed",
  "failed",
  "abandoned",
)
FEATURE_VERIFY_WORKFLOW_TERMINAL_STATUSES = ("completed", "failed", "abandoned")
FEATURE_VERIFY_WORKFLOW_STEP_STATUSES = (
  "pending",
  "running",
  "completed",
  "failed",
  "blocked",
  "skipped",
)
FEATURE_VERIFY_WORKFLOW_STEP_IDS = (
  "collect_inputs",
  "extract_criteria",
  "gather_diff",
  "feature_flag_audit",
  "code_review",
  "completeness_audit",
  "verdict",
  "finish",
)
FEATURE_SIZES = ("SMALL", "MEDIUM", "LARGE")
SPEC_INPUT_TYPES = ("raw_text", "pdf", "markdown_file", "image", "directory")
ISSUE_KEY_TYPES = ("jira", "linear", "github", "other", "none")
FEATURE_FLAG_PATTERNS = ("simple_conditional", "di_switch", "legacy", "none")
HISTORY_SIGNAL_VALUES = ("none", "irrelevant", "low", "medium", "high")
BOUNDARY_HISTORY_VALUES = HISTORY_SIGNAL_VALUES
AUDIT_RESULTS = ("all_pass", "had_gaps", "skipped")
VALIDATION_RESULTS = ("pass", "fail", "skipped")
COMPLETION_STATUSES = (
  "completed",
  "abandoned_at_planning",
  "abandoned_at_implementation",
  "abandoned_at_review",
  "error",
)
FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION = "0.1"
FEATURE_IMPLEMENT_WORKFLOW_STATUSES = (
  "pending",
  "running",
  "completed",
  "failed",
  "abandoned",
  "blocked",
)
FEATURE_IMPLEMENT_WORKFLOW_TERMINAL_STATUSES = ("completed", "failed", "abandoned")
FEATURE_IMPLEMENT_WORKFLOW_STEP_STATUSES = (
  "pending",
  "running",
  "completed",
  "failed",
  "blocked",
  "skipped",
)
FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS = (
  "assess",
  "create_branch",
  "preplan",
  "plan",
  "implement",
  "review",
  "audit",
  "validate",
  "write_history",
  "commit_push",
  "pr_description",
  "finish",
)
MEANINGFUL_NOTE_PATTERN = re.compile(r"[A-Za-z0-9]")

REVIEW_RUN_ID_PATTERN = re.compile(r"^Review run ID:\s*(?P<value>[A-Za-z0-9._:-]+)\s*$", re.MULTILINE)
REVIEW_SESSION_ID_PATTERN = re.compile(
  r"^Review session ID:\s*(?P<value>[A-Za-z0-9._:-]+)\s*$",
  re.MULTILINE,
)
SUMMARY_PATTERNS = {
  "routed_skill": re.compile(r"^Routed to:\s*(?P<value>.+?)\s*$", re.MULTILINE),
  "detected_scope": re.compile(r"^Detected review scope:\s*(?P<value>.+?)\s*$", re.MULTILINE),
  "detected_stack": re.compile(r"^Detected stack:\s*(?P<value>.+?)\s*$", re.MULTILINE),
  "execution_mode": re.compile(r"^Execution mode:\s*(?P<value>inline|delegated)\s*$", re.MULTILINE),
}
SPECIALIST_REVIEWS_PATTERN = re.compile(
  r"^(?:Specialist reviews|Baseline review|Backend specialist reviews|KMP specialist reviews):\s*(?P<value>.+?)\s*$",
  re.MULTILINE,
)
FINDING_PATTERN = re.compile(
  r"^\s*-\s+\[(?P<finding_id>F-\d{3})\]\s+"
  r"(?P<severity>Blocker|Major|Minor)\s+\|\s+"
  r"(?P<confidence>High|Medium|Low)\s+\|\s+"
  r"(?P<location>[^|]+?)\s+\|\s+"
  r"(?P<description>.+)$",
  re.MULTILINE,
)
SEVERITY_ALIASES: dict[str, str] = {
  "high": "Major",
  "medium": "Minor",
  "low": "Minor",
  "p1": "Blocker",
  "p2": "Major",
  "p3": "Minor",
  "critical": "Blocker",
  "blocker": "Blocker",
  "major": "Major",
  "minor": "Minor",
  "info": "Minor",
}

TRIAGE_DECISION_PATTERN = re.compile(
  r"^\s*(?P<number>\d+)\s+(?P<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)"
  r"(?:\s*(?:[:-]\s*|\s+)(?P<note>.+))?\s*$",
  re.IGNORECASE,
)
BULK_TRIAGE_PATTERN = re.compile(
  r"^\s*all\s+(?P<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)"
  r"(?:\s*(?:[:-]\s*|\s+)(?P<note>.+))?\s*$",
  re.IGNORECASE,
)
TRIAGE_SELECTION_ENTRY_PATTERN = re.compile(
  r"(?P<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)"
  r"\s*=\s*\[(?P<numbers>[^\]]*)\]",
  re.IGNORECASE,
)
TRIAGE_SELECTION_SEPARATOR_PATTERN = re.compile(r"[\s,]*")


@dataclass(frozen=True)
class ImportedFinding:
  finding_id: str
  severity: str
  confidence: str
  location: str
  description: str
  finding_text: str


@dataclass(frozen=True)
class ImportedReview:
  review_run_id: str
  review_session_id: str
  raw_text: str
  routed_skill: str | None
  detected_scope: str | None
  detected_stack: str | None
  execution_mode: str | None
  specialist_reviews: tuple[str, ...]
  findings: tuple[ImportedFinding, ...]


@dataclass(frozen=True)
class TriageDecision:
  number: int
  finding_id: str
  outcome_type: str
  note: str


@dataclass(frozen=True)
class TelemetrySettings:
  config_path: Path
  level: str
  enabled: bool
  install_id: str
  proxy_url: str
  custom_proxy_url: str | None
  batch_size: int


@dataclass(frozen=True)
class SyncResult:
  status: str
  synced_events: int
  pending_events: int
  config_path: Path
  telemetry_enabled: bool
  telemetry_level: str
  remote_configured: bool
  proxy_configured: bool
  sync_target: str
  proxy_url: str
  custom_proxy_url: str | None = None
  message: str | None = None
