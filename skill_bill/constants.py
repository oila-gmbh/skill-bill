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
INSTALL_ID_ENVIRONMENT_KEY = "SKILL_BILL_INSTALL_ID"
TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_BATCH_SIZE"
TELEMETRY_LEVELS = ("off", "anonymous", "full")
DEFAULT_TELEMETRY_PROXY_URL = "https://skill-bill-telemetry-proxy.skillbill.workers.dev"
DEFAULT_TELEMETRY_BATCH_SIZE = 50
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

FEATURE_IMPLEMENT_SESSION_PREFIX = "fis"
QUALITY_CHECK_SESSION_PREFIX = "qck"
FEATURE_VERIFY_SESSION_PREFIX = "fvr"
PR_DESCRIPTION_SESSION_PREFIX = "prd"
NEW_SKILL_SCAFFOLD_SESSION_PREFIX = "nss"

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
FEATURE_SIZES = ("SMALL", "MEDIUM", "LARGE")
SPEC_INPUT_TYPES = ("raw_text", "pdf", "markdown_file", "image", "directory")
ISSUE_KEY_TYPES = ("jira", "linear", "github", "other", "none")
FEATURE_FLAG_PATTERNS = ("simple_conditional", "di_switch", "legacy", "none")
BOUNDARY_HISTORY_VALUES = ("none", "irrelevant", "low", "medium", "high")
AUDIT_RESULTS = ("all_pass", "had_gaps", "skipped")
VALIDATION_RESULTS = ("pass", "fail", "skipped")
COMPLETION_STATUSES = (
  "completed",
  "abandoned_at_planning",
  "abandoned_at_implementation",
  "abandoned_at_review",
  "error",
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
