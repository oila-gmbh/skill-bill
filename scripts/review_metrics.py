#!/usr/bin/env python3

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import os
import re
import sqlite3
import sys
import urllib.error
import urllib.request
import uuid


DEFAULT_DB_PATH = Path.home() / ".skill-bill" / "review-metrics.db"
DEFAULT_CONFIG_PATH = Path.home() / ".skill-bill" / "config.json"
DB_ENVIRONMENT_KEY = "SKILL_BILL_REVIEW_DB"
CONFIG_ENVIRONMENT_KEY = "SKILL_BILL_CONFIG_PATH"
TELEMETRY_ENABLED_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_ENABLED"
TELEMETRY_PROXY_URL_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_PROXY_URL"
INSTALL_ID_ENVIRONMENT_KEY = "SKILL_BILL_INSTALL_ID"
TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY = "SKILL_BILL_TELEMETRY_BATCH_SIZE"
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
  remote_configured: bool
  proxy_configured: bool
  sync_target: str
  proxy_url: str
  custom_proxy_url: str | None = None
  message: str | None = None


def resolve_db_path(cli_value: str | None) -> Path:
  candidate = cli_value or os.environ.get(DB_ENVIRONMENT_KEY)
  if candidate:
    return Path(candidate).expanduser().resolve()
  return DEFAULT_DB_PATH.expanduser().resolve()


def resolve_config_path() -> Path:
  candidate = os.environ.get(CONFIG_ENVIRONMENT_KEY)
  if candidate:
    return Path(candidate).expanduser().resolve()
  return DEFAULT_CONFIG_PATH.expanduser().resolve()


def default_local_config() -> dict[str, object]:
  return {
    "install_id": str(uuid.uuid4()),
    "telemetry": {
      "enabled": True,
      # Leave this blank in the written config so users can clearly see
      # whether they have chosen a custom proxy override.
      "proxy_url": "",
      "batch_size": DEFAULT_TELEMETRY_BATCH_SIZE,
    },
  }


def read_local_config(path: Path) -> dict[str, object] | None:
  if not path.exists():
    return None
  try:
    raw_payload = json.loads(path.read_text(encoding="utf-8"))
  except json.JSONDecodeError as error:
    raise ValueError(f"Telemetry config at '{path}' is not valid JSON.") from error
  if not isinstance(raw_payload, dict):
    raise ValueError(f"Telemetry config at '{path}' must contain a JSON object.")
  return dict(raw_payload)


def ensure_local_config(path: Path) -> dict[str, object]:
  path.parent.mkdir(parents=True, exist_ok=True)
  payload: dict[str, object]
  raw_payload = read_local_config(path)
  payload = {} if raw_payload is None else dict(raw_payload)

  defaults = default_local_config()
  changed = False

  install_id = payload.get("install_id")
  if not isinstance(install_id, str) or not install_id.strip():
    payload["install_id"] = defaults["install_id"]
    changed = True

  telemetry_raw = payload.get("telemetry")
  telemetry = dict(telemetry_raw) if isinstance(telemetry_raw, dict) else {}
  default_telemetry = defaults["telemetry"]
  for key in ("enabled", "proxy_url", "batch_size"):
    if key not in telemetry:
      telemetry[key] = default_telemetry[key]
      changed = True
  if payload.get("telemetry") != telemetry:
    payload["telemetry"] = telemetry
    changed = True

  if changed or not path.exists():
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
  return payload


def parse_bool_value(raw_value: str, *, name: str) -> bool:
  normalized = raw_value.strip().lower()
  if normalized in ("1", "true", "yes", "on"):
    return True
  if normalized in ("0", "false", "no", "off"):
    return False
  raise ValueError(f"{name} must be one of: 1, 0, true, false, yes, no, on, off.")


def parse_positive_int(raw_value: str, *, name: str) -> int:
  try:
    value = int(raw_value)
  except ValueError as error:
    raise ValueError(f"{name} must be an integer.") from error
  if value <= 0:
    raise ValueError(f"{name} must be greater than zero.")
  return value


def load_telemetry_settings(*, materialize: bool = False) -> TelemetrySettings:
  config_path = resolve_config_path()
  config = ensure_local_config(config_path) if materialize else read_local_config(config_path)
  enabled = False
  custom_proxy_url = ""
  batch_size = DEFAULT_TELEMETRY_BATCH_SIZE
  install_id = ""

  if config is not None:
    telemetry_raw = config.get("telemetry", {})
    if telemetry_raw is None:
      telemetry: dict[str, object] = {}
    elif isinstance(telemetry_raw, dict):
      telemetry = dict(telemetry_raw)
    else:
      raise ValueError(f"Telemetry config at '{config_path}' must contain a 'telemetry' object.")

    enabled_raw = telemetry.get("enabled", True)
    if isinstance(enabled_raw, bool):
      enabled = enabled_raw
    elif isinstance(enabled_raw, str):
      enabled = parse_bool_value(enabled_raw, name="telemetry.enabled")
    else:
      enabled = bool(enabled_raw)
    custom_proxy_url = str(telemetry.get("proxy_url", "")).strip()
    batch_size_raw = telemetry.get("batch_size", DEFAULT_TELEMETRY_BATCH_SIZE)
    batch_size = (
      batch_size_raw
      if isinstance(batch_size_raw, int)
      else parse_positive_int(str(batch_size_raw), name="telemetry.batch_size")
    )
    install_id = str(config.get("install_id", "")).strip()

  if os.environ.get(TELEMETRY_ENABLED_ENVIRONMENT_KEY):
    enabled = parse_bool_value(
      os.environ[TELEMETRY_ENABLED_ENVIRONMENT_KEY],
      name=TELEMETRY_ENABLED_ENVIRONMENT_KEY,
    )
  if os.environ.get(TELEMETRY_PROXY_URL_ENVIRONMENT_KEY):
    custom_proxy_url = os.environ[TELEMETRY_PROXY_URL_ENVIRONMENT_KEY].strip()
  if os.environ.get(INSTALL_ID_ENVIRONMENT_KEY):
    install_id = os.environ[INSTALL_ID_ENVIRONMENT_KEY].strip()
  if os.environ.get(TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY):
    batch_size = parse_positive_int(
      os.environ[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY],
      name=TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY,
    )

  hosted_relay_url = DEFAULT_TELEMETRY_PROXY_URL.rstrip("/")
  normalized_custom_proxy_url = custom_proxy_url.rstrip("/")
  if normalized_custom_proxy_url == hosted_relay_url:
    normalized_custom_proxy_url = ""
  proxy_url = normalized_custom_proxy_url or hosted_relay_url
  if enabled and not install_id:
    raise ValueError(
      f"Telemetry is enabled but no install_id is configured at '{config_path}'. "
      "Run 'python3 scripts/review_metrics.py telemetry enable' to create one."
    )

  return TelemetrySettings(
    config_path=config_path,
    enabled=enabled,
    install_id=install_id,
    proxy_url=proxy_url,
    custom_proxy_url=normalized_custom_proxy_url or None,
    batch_size=batch_size,
  )


def telemetry_is_enabled() -> bool:
  try:
    return load_telemetry_settings().enabled
  except ValueError:
    return False


def purge_telemetry_outbox(db_path: Path) -> int:
  if not db_path.exists():
    return 0
  connection = sqlite3.connect(db_path)
  try:
    row = connection.execute(
      """
      SELECT 1
      FROM sqlite_master
      WHERE type = 'table' AND name = 'telemetry_outbox'
      """
    ).fetchone()
    if row is None:
      return 0
    count_row = connection.execute("SELECT COUNT(*) FROM telemetry_outbox").fetchone()
    pending_events = 0 if count_row is None else int(count_row[0])
    with connection:
      connection.execute("DELETE FROM telemetry_outbox")
    return pending_events
  finally:
    connection.close()


def set_telemetry_enabled(enabled: bool, *, db_path: Path | None = None) -> tuple[TelemetrySettings, int]:
  config_path = resolve_config_path()
  if enabled:
    payload = ensure_local_config(config_path)
    telemetry = payload["telemetry"]
    if not isinstance(telemetry, dict):
      raise ValueError(f"Telemetry config at '{config_path}' must contain a 'telemetry' object.")
    telemetry["enabled"] = True
    payload["telemetry"] = telemetry
    config_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return (load_telemetry_settings(materialize=True), 0)

  if config_path.exists():
    config_path.unlink()
  cleared_events = 0 if db_path is None else purge_telemetry_outbox(db_path)
  return (load_telemetry_settings(), cleared_events)


def ensure_database(path: Path) -> sqlite3.Connection:
  path.parent.mkdir(parents=True, exist_ok=True)
  connection = sqlite3.connect(path)
  connection.execute("PRAGMA foreign_keys = ON")
  connection.row_factory = sqlite3.Row
  connection.executescript(
    """
    CREATE TABLE IF NOT EXISTS review_runs (
      review_run_id TEXT PRIMARY KEY,
      review_session_id TEXT,
      routed_skill TEXT,
      detected_scope TEXT,
      detected_stack TEXT,
      execution_mode TEXT,
      source_path TEXT,
      raw_text TEXT NOT NULL,
      review_finished_at TEXT,
      review_finished_event_emitted_at TEXT,
      imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS findings (
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      severity TEXT NOT NULL,
      confidence TEXT NOT NULL,
      location TEXT NOT NULL,
      description TEXT NOT NULL,
      finding_text TEXT NOT NULL,
      PRIMARY KEY (review_run_id, finding_id),
      FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS feedback_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      event_type TEXT NOT NULL CHECK (
        event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
      ),
      note TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_feedback_events_run
      ON feedback_events(review_run_id, finding_id, id);

    CREATE TABLE IF NOT EXISTS learnings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      scope TEXT NOT NULL CHECK (scope IN ('global', 'repo', 'skill')),
      scope_key TEXT NOT NULL DEFAULT '',
      title TEXT NOT NULL,
      rule_text TEXT NOT NULL,
      rationale TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL CHECK (status IN ('active', 'disabled')) DEFAULT 'active',
      source_review_run_id TEXT,
      source_finding_id TEXT,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      CHECK ((source_review_run_id IS NULL) = (source_finding_id IS NULL)),
      FOREIGN KEY (source_review_run_id, source_finding_id)
        REFERENCES findings(review_run_id, finding_id)
        ON DELETE SET NULL
    );

    CREATE INDEX IF NOT EXISTS idx_learnings_scope
      ON learnings(scope, scope_key, status);

    CREATE TABLE IF NOT EXISTS telemetry_outbox (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_name TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      synced_at TEXT,
      last_error TEXT NOT NULL DEFAULT ''
    );

    CREATE INDEX IF NOT EXISTS idx_telemetry_outbox_pending
      ON telemetry_outbox(synced_at, id);

    CREATE TABLE IF NOT EXISTS session_learnings (
      review_session_id TEXT PRIMARY KEY,
      learnings_json TEXT NOT NULL,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    """
  )
  ensure_column(connection, "review_runs", "review_session_id", "TEXT")
  ensure_column(connection, "review_runs", "review_finished_at", "TEXT")
  ensure_column(connection, "review_runs", "review_finished_event_emitted_at", "TEXT")
  ensure_column(connection, "review_runs", "specialist_reviews", "TEXT NOT NULL DEFAULT ''")
  backfill_review_session_ids(connection)
  migrate_feedback_events_schema(connection)
  return connection


SAFE_IDENTIFIER_PATTERN = re.compile(r"^[a-z_][a-z0-9_]*$")


def ensure_column(
  connection: sqlite3.Connection,
  table_name: str,
  column_name: str,
  definition: str,
) -> None:
  if not SAFE_IDENTIFIER_PATTERN.match(table_name):
    raise ValueError(f"Unsafe table name: '{table_name}'")
  if not SAFE_IDENTIFIER_PATTERN.match(column_name):
    raise ValueError(f"Unsafe column name: '{column_name}'")
  columns = connection.execute(f"PRAGMA table_info({table_name})").fetchall()
  if any(str(column["name"]) == column_name for column in columns):
    return
  with connection:
    connection.execute(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {definition}")


def backfill_review_session_ids(connection: sqlite3.Connection) -> None:
  with connection:
    connection.execute(
      """
      UPDATE review_runs
      SET review_session_id = review_run_id
      WHERE review_session_id IS NULL OR review_session_id = ''
      """
    )


def migrate_feedback_events_schema(connection: sqlite3.Connection) -> None:
  row = connection.execute(
    """
    SELECT sql
    FROM sqlite_master
    WHERE type = 'table' AND name = 'feedback_events'
    """
  ).fetchone()
  if row is None:
    return

  create_sql = str(row["sql"] or "")
  has_current_schema = all(f"'{event_type}'" in create_sql for event_type in FINDING_OUTCOME_TYPES)
  has_legacy_schema = any(
    legacy_event in create_sql
    for legacy_event in ("'accepted'", "'dismissed'", "'fix_requested'")
  )
  if has_current_schema and not has_legacy_schema:
    return

  rows = connection.execute(
    """
    SELECT id, review_run_id, finding_id, event_type, note, created_at
    FROM feedback_events
    ORDER BY id
    """
  ).fetchall()
  migrated_rows = [
    (
      int(row["id"]),
      str(row["review_run_id"]),
      str(row["finding_id"]),
      normalize_feedback_event_type(str(row["event_type"])),
      str(row["note"] or ""),
      str(row["created_at"]),
    )
    for row in rows
  ]

  with connection:
    connection.execute("ALTER TABLE feedback_events RENAME TO feedback_events_legacy")
    connection.executescript(
      """
      CREATE TABLE feedback_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        event_type TEXT NOT NULL CHECK (
          event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
        ),
        note TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_feedback_events_run
        ON feedback_events(review_run_id, finding_id, id);
      """
    )
    if migrated_rows:
      connection.executemany(
        """
        INSERT INTO feedback_events (id, review_run_id, finding_id, event_type, note, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        migrated_rows,
      )
    connection.execute("DROP TABLE feedback_events_legacy")


def normalize_feedback_event_type(event_type: str) -> str:
  legacy_mapping = {
    "accepted": "finding_accepted",
    "dismissed": "fix_rejected",
    "fix_requested": "fix_applied",
  }
  normalized_event_type = legacy_mapping.get(event_type, event_type)
  if normalized_event_type not in FINDING_OUTCOME_TYPES:
    raise ValueError(f"Unsupported finding outcome '{event_type}'.")
  return normalized_event_type


def parse_review(text: str) -> ImportedReview:
  review_run_match = REVIEW_RUN_ID_PATTERN.search(text)
  if not review_run_match:
    raise ValueError("Review output is missing 'Review run ID: <review-run-id>'.")
  review_run_id = review_run_match.group("value")
  review_session_match = REVIEW_SESSION_ID_PATTERN.search(text)
  if not review_session_match:
    raise ValueError("Review output is missing 'Review session ID: <review-session-id>'.")
  review_session_id = review_session_match.group("value")

  findings: list[ImportedFinding] = []
  seen_ids: set[str] = set()
  for match in FINDING_PATTERN.finditer(text):
    finding_id = match.group("finding_id")
    if finding_id in seen_ids:
      raise ValueError(f"Review output contains duplicate finding id '{finding_id}'.")
    seen_ids.add(finding_id)
    findings.append(
      ImportedFinding(
        finding_id=finding_id,
        severity=match.group("severity"),
        confidence=match.group("confidence"),
        location=match.group("location").strip(),
        description=match.group("description").strip(),
        finding_text=match.group(0).strip(),
      )
    )

  return ImportedReview(
    review_run_id=review_run_id,
    review_session_id=review_session_id,
    raw_text=text,
    routed_skill=extract_summary_value(text, "routed_skill"),
    detected_scope=extract_summary_value(text, "detected_scope"),
    detected_stack=extract_summary_value(text, "detected_stack"),
    execution_mode=extract_summary_value(text, "execution_mode"),
    specialist_reviews=extract_specialist_reviews(text),
    findings=tuple(findings),
  )


def extract_summary_value(text: str, key: str) -> str | None:
  match = SUMMARY_PATTERNS[key].search(text)
  if not match:
    return None
  return match.group("value").strip()


def extract_specialist_reviews(text: str) -> tuple[str, ...]:
  seen: list[str] = []
  for match in SPECIALIST_REVIEWS_PATTERN.finditer(text):
    for name in match.group("value").split(","):
      stripped = name.strip()
      if stripped and stripped not in seen:
        seen.append(stripped)
  return tuple(seen)


def read_input(input_path: str) -> tuple[str, str | None]:
  if input_path == "-":
    return (sys.stdin.read(), None)
  path = Path(input_path).expanduser().resolve()
  return (path.read_text(encoding="utf-8"), str(path))


def save_imported_review(
  connection: sqlite3.Connection,
  review: ImportedReview,
  *,
  source_path: str | None,
) -> None:
  telemetry_enabled = telemetry_is_enabled()
  existing_review_summary = connection.execute(
    """
    SELECT review_session_id, routed_skill, detected_scope, detected_stack, execution_mode, specialist_reviews
    FROM review_runs
    WHERE review_run_id = ?
    """,
    (review.review_run_id,),
  ).fetchone()
  existing_findings = fetch_imported_findings(connection, review.review_run_id)
  summary_snapshot_changed = (
    existing_review_summary is None
    or existing_review_summary["review_session_id"] != review.review_session_id
    or existing_review_summary["routed_skill"] != review.routed_skill
    or existing_review_summary["detected_scope"] != review.detected_scope
    or existing_review_summary["detected_stack"] != review.detected_stack
    or existing_review_summary["execution_mode"] != review.execution_mode
    or existing_review_summary["specialist_reviews"] != ",".join(review.specialist_reviews)
    or existing_findings != review.findings
  )
  with connection:
    connection.execute(
      """
      INSERT INTO review_runs (
        review_run_id,
        review_session_id,
        routed_skill,
        detected_scope,
        detected_stack,
        execution_mode,
        specialist_reviews,
        source_path,
        raw_text
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(review_run_id) DO UPDATE SET
        review_session_id = excluded.review_session_id,
        routed_skill = excluded.routed_skill,
        detected_scope = excluded.detected_scope,
        detected_stack = excluded.detected_stack,
        execution_mode = excluded.execution_mode,
        specialist_reviews = excluded.specialist_reviews,
        source_path = excluded.source_path,
        raw_text = excluded.raw_text
      """,
      (
        review.review_run_id,
        review.review_session_id,
        review.routed_skill,
        review.detected_scope,
        review.detected_stack,
        review.execution_mode,
        ",".join(review.specialist_reviews),
        source_path,
        review.raw_text,
      ),
    )

    if summary_snapshot_changed:
      clear_review_finished_telemetry_state(connection, review.review_run_id)

    if existing_findings != review.findings:
      connection.execute(
        "DELETE FROM findings WHERE review_run_id = ?",
        (review.review_run_id,),
      )
      for finding in review.findings:
        connection.execute(
          """
          INSERT INTO findings (
            review_run_id,
            finding_id,
            severity,
            confidence,
            location,
            description,
            finding_text
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          (
            review.review_run_id,
            finding.finding_id,
            finding.severity,
            finding.confidence,
            finding.location,
            finding.description,
            finding.finding_text,
          ),
        )

    update_review_finished_telemetry_state(
      connection,
      review_run_id=review.review_run_id,
      enabled=telemetry_enabled,
    )


def fetch_imported_findings(
  connection: sqlite3.Connection,
  review_run_id: str,
) -> tuple[ImportedFinding, ...]:
  rows = connection.execute(
    """
    SELECT finding_id, severity, confidence, location, description, finding_text
    FROM findings
    WHERE review_run_id = ?
    ORDER BY finding_id
    """,
    (review_run_id,),
  ).fetchall()
  return tuple(
    ImportedFinding(
      finding_id=str(row["finding_id"]),
      severity=str(row["severity"]),
      confidence=str(row["confidence"]),
      location=str(row["location"]),
      description=str(row["description"]),
      finding_text=str(row["finding_text"]),
    )
    for row in rows
  )


def enqueue_telemetry_event(
  connection: sqlite3.Connection,
  *,
  event_name: str,
  payload: dict[str, object],
  enabled: bool | None = None,
) -> None:
  if enabled is None:
    enabled = telemetry_is_enabled()
  if not enabled:
    return
  connection.execute(
    """
    INSERT INTO telemetry_outbox (event_name, payload_json)
    VALUES (?, ?)
    """,
    (
      event_name,
      json.dumps(payload, sort_keys=True),
    ),
  )


def pending_telemetry_count(connection: sqlite3.Connection) -> int:
  row = connection.execute(
    "SELECT COUNT(*) FROM telemetry_outbox WHERE synced_at IS NULL"
  ).fetchone()
  if row is None:
    return 0
  return int(row[0])


def latest_telemetry_error(connection: sqlite3.Connection) -> str | None:
  row = connection.execute(
    """
    SELECT last_error
    FROM telemetry_outbox
    WHERE synced_at IS NULL AND last_error != ''
    ORDER BY id DESC
    LIMIT 1
    """
  ).fetchone()
  if row is None:
    return None
  return str(row[0]).strip() or None


def fetch_pending_telemetry_events(
  connection: sqlite3.Connection,
  *,
  limit: int,
) -> list[sqlite3.Row]:
  return connection.execute(
    """
    SELECT id, event_name, payload_json, created_at
    FROM telemetry_outbox
    WHERE synced_at IS NULL
    ORDER BY id
    LIMIT ?
    """,
    (limit,),
  ).fetchall()


def mark_telemetry_synced(connection: sqlite3.Connection, event_ids: list[int]) -> None:
  if not event_ids:
    return
  placeholders = ", ".join("?" for _ in event_ids)
  with connection:
    connection.execute(
      f"""
      UPDATE telemetry_outbox
      SET synced_at = CURRENT_TIMESTAMP,
          last_error = ''
      WHERE id IN ({placeholders})
      """,
      tuple(event_ids),
    )


def mark_telemetry_failed(connection: sqlite3.Connection, *, event_ids: list[int], error_message: str) -> None:
  if not event_ids:
    return
  placeholders = ", ".join("?" for _ in event_ids)
  with connection:
    connection.execute(
      f"""
      UPDATE telemetry_outbox
      SET last_error = ?
      WHERE id IN ({placeholders})
      """,
      tuple([error_message] + event_ids),
    )


def record_feedback(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  finding_ids: list[str],
  event_type: str,
  note: str,
) -> None:
  if not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'. Import the review first.")

  missing_findings = [
    finding_id
    for finding_id in finding_ids
    if not finding_exists(connection, review_run_id, finding_id)
  ]
  if missing_findings:
    raise ValueError(
      "Unknown finding ids for review run "
      f"'{review_run_id}': {', '.join(sorted(missing_findings))}"
    )

  telemetry_enabled = telemetry_is_enabled()
  with connection:
    for finding_id in finding_ids:
      connection.execute(
        """
        INSERT INTO feedback_events (review_run_id, finding_id, event_type, note)
        VALUES (?, ?, ?, ?)
        """,
        (review_run_id, finding_id, event_type, note),
      )

    update_review_finished_telemetry_state(
      connection,
      review_run_id=review_run_id,
      enabled=telemetry_enabled,
    )


def review_exists(connection: sqlite3.Connection, review_run_id: str) -> bool:
  row = connection.execute(
    "SELECT 1 FROM review_runs WHERE review_run_id = ?",
    (review_run_id,),
  ).fetchone()
  return row is not None


def finding_exists(connection: sqlite3.Connection, review_run_id: str, finding_id: str) -> bool:
  row = connection.execute(
    """
    SELECT 1
    FROM findings
    WHERE review_run_id = ? AND finding_id = ?
    """,
    (review_run_id, finding_id),
  ).fetchone()
  return row is not None


def fetch_review_summary(connection: sqlite3.Connection, review_run_id: str) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      specialist_reviews,
      review_finished_at,
      review_finished_event_emitted_at
    FROM review_runs
    WHERE review_run_id = ?
    """,
    (review_run_id,),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown review run id '{review_run_id}'.")
  return row


def fetch_finding_metadata(connection: sqlite3.Connection, review_run_id: str, finding_id: str) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT finding_id, severity, confidence
    FROM findings
    WHERE review_run_id = ? AND finding_id = ?
    """,
    (review_run_id, finding_id),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown finding id '{finding_id}' for review run '{review_run_id}'.")
  return row


def fetch_numbered_findings(connection: sqlite3.Connection, review_run_id: str) -> list[dict[str, object]]:
  if not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'.")

  rows = connection.execute(
    """
    SELECT finding_id, severity, confidence, location, description
    FROM findings
    WHERE review_run_id = ?
    ORDER BY finding_id
    """,
    (review_run_id,),
  ).fetchall()

  numbered_findings: list[dict[str, object]] = []
  for index, row in enumerate(rows, start=1):
    numbered_findings.append(
      {
        "number": index,
        "finding_id": row["finding_id"],
        "severity": row["severity"],
        "confidence": row["confidence"],
        "location": row["location"],
        "description": row["description"],
      }
    )
  return numbered_findings


def count_learnings(connection: sqlite3.Connection, *, status: str | None = None) -> int:
  query = "SELECT COUNT(*) FROM learnings"
  parameters: list[str] = []
  if status is not None:
    query += " WHERE status = ?"
    parameters.append(status)
  row = connection.execute(query, tuple(parameters)).fetchone()
  if row is None:
    return 0
  return int(row[0])


def expand_bulk_decisions(
  raw_decisions: list[str],
  numbered_findings: list[dict[str, object]],
) -> list[str]:
  expanded: list[str] = []
  for raw_decision in raw_decisions:
    bulk_match = BULK_TRIAGE_PATTERN.fullmatch(raw_decision.strip())
    if bulk_match:
      action = bulk_match.group("action")
      note = bulk_match.group("note") or ""
      for entry in numbered_findings:
        suffix = f" - {note}" if note.strip() else ""
        expanded.append(f"{entry['number']} {action}{suffix}")
    else:
      expanded.append(raw_decision)
  return expanded


def parse_triage_decisions(
  raw_decisions: list[str],
  numbered_findings: list[dict[str, object]],
) -> list[TriageDecision]:
  expanded_decisions = expand_bulk_decisions(raw_decisions, numbered_findings)
  number_to_finding = {
    int(entry["number"]): str(entry["finding_id"])
    for entry in numbered_findings
  }
  decisions: list[TriageDecision] = []
  seen_numbers: set[int] = set()

  for raw_decision in expanded_decisions:
    match = TRIAGE_DECISION_PATTERN.fullmatch(raw_decision.strip())
    if not match:
      raise ValueError(
        "Invalid triage decision format. Use entries like '1 fix', "
        "'2 skip - intentional', 'all fix', or 'all skip - reason'."
      )

    number = int(match.group("number"))
    if number not in number_to_finding:
      raise ValueError(f"Unknown finding number '{number}' for the current review run.")
    if number in seen_numbers:
      raise ValueError(f"Duplicate triage decision for finding number '{number}'.")
    seen_numbers.add(number)

    decisions.append(
      TriageDecision(
        number=number,
        finding_id=number_to_finding[number],
        outcome_type=normalize_triage_action(match.group("action")),
        note=normalize_triage_note(match.group("note")),
      )
    )

  return decisions


def normalize_triage_action(raw_action: str) -> str:
  action = raw_action.strip().lower()
  if action in ("false positive", "false-positive", "false_positive"):
    return "false_positive"
  if action == "fix":
    return "fix_applied"
  if action in ("accept", "accepted"):
    return "finding_accepted"
  if action in ("edit", "edited"):
    return "finding_edited"
  if action in ("dismiss", "skip", "reject"):
    return "fix_rejected"
  raise ValueError(f"Unsupported triage action '{raw_action}'.")


def normalize_triage_note(raw_note: str | None) -> str:
  note = (raw_note or "").strip()
  if note and not MEANINGFUL_NOTE_PATTERN.search(note):
    return ""
  return note


def stats_payload(connection: sqlite3.Connection, review_run_id: str | None) -> dict[str, object]:
  if review_run_id and not review_exists(connection, review_run_id):
    raise ValueError(f"Unknown review run id '{review_run_id}'.")

  finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  payload = summarize_finding_rows(finding_rows)
  payload["review_run_id"] = review_run_id
  return payload


def count_rows(
  connection: sqlite3.Connection,
  base_query: str,
  *,
  review_run_id: str | None = None,
) -> int:
  query = base_query
  parameters: list[str] = []
  if review_run_id:
    query += " WHERE review_run_id = ?"
    parameters.append(review_run_id)
  row = connection.execute(query, tuple(parameters)).fetchone()
  if row is None:
    return 0
  return int(row[0])


def empty_severity_counts() -> dict[str, int]:
  return {"Blocker": 0, "Major": 0, "Minor": 0}


def latest_finding_outcomes(
  connection: sqlite3.Connection,
  *,
  review_run_id: str | None = None,
) -> list[sqlite3.Row]:
  parameters: list[str] = []
  latest_feedback_filter = ""
  findings_filter = ""
  if review_run_id:
    latest_feedback_filter = "WHERE review_run_id = ?"
    findings_filter = "WHERE f.review_run_id = ?"
    parameters.append(review_run_id)
    parameters.append(review_run_id)

  return connection.execute(
    f"""
    WITH latest_feedback AS (
      SELECT review_run_id, finding_id, MAX(id) AS latest_id
      FROM feedback_events
      {latest_feedback_filter}
      GROUP BY review_run_id, finding_id
    )
    SELECT
      f.review_run_id,
      f.finding_id,
      f.severity,
      f.confidence,
      f.location,
      f.description,
      COALESCE(fe.event_type, '') AS outcome_type,
      COALESCE(fe.note, '') AS note
    FROM findings f
    LEFT JOIN latest_feedback lf
      ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
    LEFT JOIN feedback_events fe
      ON fe.id = lf.latest_id
    {findings_filter}
    ORDER BY f.review_run_id, f.finding_id
    """,
    tuple(parameters),
  ).fetchall()


def summarize_finding_rows(finding_rows: list[sqlite3.Row]) -> dict[str, object]:
  outcome_counts = {outcome_type: 0 for outcome_type in FINDING_OUTCOME_TYPES}
  accepted_severity_counts = empty_severity_counts()
  rejected_severity_counts = empty_severity_counts()
  unresolved_severity_counts = empty_severity_counts()
  rejected_findings: list[dict[str, object]] = []
  accepted_findings = 0
  rejected_findings_count = 0
  unresolved_findings = 0
  rejected_findings_with_notes = 0

  for row in finding_rows:
    severity = str(row["severity"])
    outcome_type = str(row["outcome_type"] or "")
    note = str(row["note"] or "")
    if outcome_type in FINDING_OUTCOME_TYPES:
      outcome_counts[outcome_type] += 1

    if outcome_type in ACCEPTED_FINDING_OUTCOME_TYPES:
      accepted_findings += 1
      accepted_severity_counts[severity] += 1
      continue

    if outcome_type in REJECTED_FINDING_OUTCOME_TYPES:
      rejected_findings_count += 1
      rejected_severity_counts[severity] += 1
      rejected_payload: dict[str, object] = {
        "finding_id": row["finding_id"],
        "severity": severity,
        "confidence": row["confidence"],
        "location": row["location"],
        "description": row["description"],
        "outcome_type": outcome_type,
      }
      if note:
        rejected_payload["note"] = note
        rejected_findings_with_notes += 1
      rejected_findings.append(rejected_payload)
      continue

    unresolved_findings += 1
    unresolved_severity_counts[severity] += 1

  total_findings = len(finding_rows)
  accepted_rate = round(accepted_findings / total_findings, 3) if total_findings else 0.0
  rejected_rate = round(rejected_findings_count / total_findings, 3) if total_findings else 0.0

  return {
    "total_findings": total_findings,
    "accepted_findings": accepted_findings,
    "rejected_findings": rejected_findings_count,
    "unresolved_findings": unresolved_findings,
    "accepted_rate": accepted_rate,
    "rejected_rate": rejected_rate,
    "latest_outcome_counts": outcome_counts,
    "accepted_severity_counts": accepted_severity_counts,
    "rejected_severity_counts": rejected_severity_counts,
    "unresolved_severity_counts": unresolved_severity_counts,
    "rejected_findings_with_notes": rejected_findings_with_notes,
    "rejected_finding_details": rejected_findings,
  }


def clear_review_finished_telemetry_state(
  connection: sqlite3.Connection,
  review_run_id: str,
) -> None:
  connection.execute(
    """
    UPDATE review_runs
    SET review_finished_at = NULL,
        review_finished_event_emitted_at = NULL
    WHERE review_run_id = ?
    """,
    (review_run_id,),
  )


def save_session_learnings(
  connection: sqlite3.Connection,
  *,
  review_session_id: str,
  learnings_json: str,
) -> None:
  connection.execute(
    """
    INSERT INTO session_learnings (review_session_id, learnings_json, updated_at)
    VALUES (?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(review_session_id) DO UPDATE SET
      learnings_json = excluded.learnings_json,
      updated_at = CURRENT_TIMESTAMP
    """,
    (review_session_id, learnings_json),
  )


def fetch_session_learnings(
  connection: sqlite3.Connection,
  review_session_id: str,
) -> dict[str, object] | None:
  row = connection.execute(
    "SELECT learnings_json FROM session_learnings WHERE review_session_id = ?",
    (review_session_id,),
  ).fetchone()
  if row is None:
    return None
  try:
    return json.loads(str(row[0]))
  except (json.JSONDecodeError, TypeError):
    return None


def build_review_finished_payload(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  review_summary: sqlite3.Row | None = None,
  finding_rows: list[sqlite3.Row] | None = None,
) -> dict[str, object]:
  if review_summary is None:
    review_summary = fetch_review_summary(connection, review_run_id)
  if finding_rows is None:
    finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  payload = summarize_finding_rows(finding_rows)
  for key in ("rejected_findings", "rejected_rate", "rejected_findings_with_notes"):
    payload.pop(key, None)
  specialist_reviews_raw = str(review_summary["specialist_reviews"] or "")
  specialist_reviews = [s.strip() for s in specialist_reviews_raw.split(",") if s.strip()]
  session_id = str(review_summary["review_session_id"] or "")
  learnings_data = fetch_session_learnings(connection, session_id) if session_id else None
  payload.update(
    {
      "review_session_id": session_id,
      "routed_skill": review_summary["routed_skill"],
      "review_subskills": specialist_reviews,
      "review_scope": review_summary["detected_scope"],
      "review_platform": review_summary["detected_stack"],
      "execution_mode": review_summary["execution_mode"],
      "review_finished_at": review_summary["review_finished_at"],
      "applied_learning_count": learnings_data.get("applied_learning_count", 0) if learnings_data else 0,
      "applied_learning_references": learnings_data.get("applied_learning_references", []) if learnings_data else [],
      "applied_learnings": learnings_data.get("applied_learnings", "none") if learnings_data else "none",
      "scope_counts": learnings_data.get("scope_counts", {}) if learnings_data else {},
      "learnings": learnings_data.get("learnings", []) if learnings_data else [],
    }
  )
  return payload


def update_review_finished_telemetry_state(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  enabled: bool | None = None,
) -> None:
  if enabled is None:
    enabled = telemetry_is_enabled()
  review_summary = fetch_review_summary(connection, review_run_id)
  finding_rows = latest_finding_outcomes(connection, review_run_id=review_run_id)
  summarized_findings = summarize_finding_rows(finding_rows)

  if int(summarized_findings["unresolved_findings"]) > 0:
    if review_summary["review_finished_at"] or review_summary["review_finished_event_emitted_at"]:
      clear_review_finished_telemetry_state(connection, review_run_id)
    return

  if not review_summary["review_finished_at"]:
    connection.execute(
      """
      UPDATE review_runs
      SET review_finished_at = CURRENT_TIMESTAMP
      WHERE review_run_id = ? AND review_finished_at IS NULL
      """,
      (review_run_id,),
    )
    review_summary = fetch_review_summary(connection, review_run_id)

  payload = build_review_finished_payload(
    connection,
    review_run_id=review_run_id,
    review_summary=review_summary,
    finding_rows=finding_rows,
  )

  if review_summary["review_finished_event_emitted_at"]:
    if enabled:
      update_pending_review_finished_event(connection, review_run_id=review_run_id, payload=payload)
    return

  enqueue_telemetry_event(
    connection,
    event_name="skillbill_review_finished",
    payload=payload,
    enabled=enabled,
  )
  if enabled:
    connection.execute(
      """
      UPDATE review_runs
      SET review_finished_event_emitted_at = CURRENT_TIMESTAMP
      WHERE review_run_id = ?
      """,
      (review_run_id,),
    )


def update_pending_review_finished_event(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  payload: dict[str, object],
) -> None:
  connection.execute(
    """
    UPDATE telemetry_outbox
    SET payload_json = ?
    WHERE event_name = 'skillbill_review_finished'
      AND synced_at IS NULL
      AND json_extract(payload_json, '$.review_run_id') = ?
    """,
    (json.dumps(payload, sort_keys=True), review_run_id),
  )


def validate_learning_scope(scope: str, scope_key: str) -> tuple[str, str]:
  if scope not in LEARNING_SCOPES:
    raise ValueError(f"Learning scope must be one of {', '.join(LEARNING_SCOPES)}.")

  normalized_scope_key = scope_key.strip()
  if scope == "global":
    return (scope, "")
  if not normalized_scope_key:
    raise ValueError(f"Learning scope '{scope}' requires a non-empty --scope-key.")
  return (scope, normalized_scope_key)


def validate_learning_source(
  connection: sqlite3.Connection,
  *,
  source_review_run_id: str | None,
  source_finding_id: str | None,
) -> tuple[str, str, sqlite3.Row]:
  if not source_review_run_id or not source_finding_id:
    raise ValueError(
      "Learnings must be derived from a rejected review finding. "
      "Provide both --from-run and --from-finding."
    )

  if not finding_exists(connection, source_review_run_id, source_finding_id):
    raise ValueError(
      "Unknown learning source "
      f"'{source_review_run_id}:{source_finding_id}'. Import the review and finding first."
    )

  rejected_outcome = fetch_latest_rejected_outcome(
    connection,
    review_run_id=source_review_run_id,
    finding_id=source_finding_id,
  )
  if rejected_outcome is None:
    raise ValueError(
      f"Finding '{source_finding_id}' in run '{source_review_run_id}' has no rejected outcome. "
      "Learnings can only be created from findings the user rejected (fix_rejected or false_positive)."
    )

  return (source_review_run_id, source_finding_id, rejected_outcome)


def fetch_latest_rejected_outcome(
  connection: sqlite3.Connection,
  *,
  review_run_id: str,
  finding_id: str,
) -> sqlite3.Row | None:
  placeholders = ", ".join("?" for _ in REJECTED_FINDING_OUTCOME_TYPES)
  return connection.execute(
    f"""
    SELECT event_type, note
    FROM feedback_events
    WHERE review_run_id = ? AND finding_id = ? AND event_type IN ({placeholders})
    ORDER BY id DESC
    LIMIT 1
    """,
    (review_run_id, finding_id, *REJECTED_FINDING_OUTCOME_TYPES),
  ).fetchone()


def normalize_optional_lookup_value(raw_value: str | None, argument_name: str) -> str | None:
  if raw_value is None:
    return None
  normalized = raw_value.strip()
  if not normalized:
    raise ValueError(f"{argument_name} must not be empty when provided.")
  return normalized


def add_learning(
  connection: sqlite3.Connection,
  *,
  scope: str,
  scope_key: str,
  title: str,
  rule_text: str,
  rationale: str,
  source_review_run_id: str | None,
  source_finding_id: str | None,
) -> int:
  scope, scope_key = validate_learning_scope(scope, scope_key)
  source_review_run_id, source_finding_id, rejected_outcome = validate_learning_source(
    connection,
    source_review_run_id=source_review_run_id,
    source_finding_id=source_finding_id,
  )

  if not rationale.strip() and str(rejected_outcome["note"] or "").strip():
    rationale = str(rejected_outcome["note"]).strip()

  if not title.strip():
    raise ValueError("Learning title must not be empty.")
  if not rule_text.strip():
    raise ValueError("Learning rule text must not be empty.")

  with connection:
    cursor = connection.execute(
      """
      INSERT INTO learnings (
        scope,
        scope_key,
        title,
        rule_text,
        rationale,
        status,
        source_review_run_id,
        source_finding_id
      ) VALUES (?, ?, ?, ?, ?, 'active', ?, ?)
      """,
      (
        scope,
        scope_key,
        title.strip(),
        rule_text.strip(),
        rationale.strip(),
        source_review_run_id,
        source_finding_id,
      ),
    )
    learning_id = int(cursor.lastrowid)
  return learning_id


def get_learning(connection: sqlite3.Connection, learning_id: int) -> sqlite3.Row:
  row = connection.execute(
    """
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
    WHERE id = ?
    """,
    (learning_id,),
  ).fetchone()
  if row is None:
    raise ValueError(f"Unknown learning id '{learning_id}'.")
  return row


def list_learnings(
  connection: sqlite3.Connection,
  *,
  status: str,
) -> list[sqlite3.Row]:
  parameters: list[str] = []
  query = """
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
  """
  if status != "all":
    query += " WHERE status = ?"
    parameters.append(status)
  query += " ORDER BY id"
  return connection.execute(query, tuple(parameters)).fetchall()


def resolve_learnings(
  connection: sqlite3.Connection,
  *,
  repo_scope_key: str | None,
  skill_name: str | None,
) -> tuple[str | None, str | None, list[sqlite3.Row]]:
  repo_scope_key = normalize_optional_lookup_value(repo_scope_key, "--repo")
  skill_name = normalize_optional_lookup_value(skill_name, "--skill")

  scope_clauses = ["scope = 'global'"]
  parameters: list[str] = []
  if repo_scope_key is not None:
    scope_clauses.append("(scope = 'repo' AND scope_key = ?)")
    parameters.append(repo_scope_key)
  if skill_name is not None:
    scope_clauses.append("(scope = 'skill' AND scope_key = ?)")
    parameters.append(skill_name)

  rows = connection.execute(
    f"""
    SELECT
      id,
      scope,
      scope_key,
      title,
      rule_text,
      rationale,
      status,
      source_review_run_id,
      source_finding_id,
      created_at,
      updated_at
    FROM learnings
    WHERE status = 'active'
      AND ({' OR '.join(scope_clauses)})
    ORDER BY
      CASE scope
        WHEN 'skill' THEN 0
        WHEN 'repo' THEN 1
        ELSE 2
      END,
      id
    """,
    tuple(parameters),
  ).fetchall()
  return (repo_scope_key, skill_name, list(rows))


def edit_learning(
  connection: sqlite3.Connection,
  *,
  learning_id: int,
  scope: str | None,
  scope_key: str | None,
  title: str | None,
  rule_text: str | None,
  rationale: str | None,
) -> sqlite3.Row:
  current = get_learning(connection, learning_id)

  next_scope = current["scope"] if scope is None else scope
  next_scope_key = current["scope_key"] if scope_key is None else scope_key
  next_scope, next_scope_key = validate_learning_scope(next_scope, next_scope_key)
  next_title = current["title"] if title is None else title.strip()
  next_rule_text = current["rule_text"] if rule_text is None else rule_text.strip()
  next_rationale = current["rationale"] if rationale is None else rationale.strip()

  if not next_title:
    raise ValueError("Learning title must not be empty.")
  if not next_rule_text:
    raise ValueError("Learning rule text must not be empty.")

  with connection:
    connection.execute(
      """
      UPDATE learnings
      SET scope = ?,
          scope_key = ?,
          title = ?,
          rule_text = ?,
          rationale = ?,
          updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """,
      (
        next_scope,
        next_scope_key,
        next_title,
        next_rule_text,
        next_rationale,
        learning_id,
        ),
      )
  return get_learning(connection, learning_id)


def set_learning_status(
  connection: sqlite3.Connection,
  *,
  learning_id: int,
  status: str,
) -> sqlite3.Row:
  if status not in LEARNING_STATUSES:
    raise ValueError(f"Learning status must be one of {', '.join(LEARNING_STATUSES)}.")
  get_learning(connection, learning_id)
  with connection:
    connection.execute(
      """
      UPDATE learnings
      SET status = ?, updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """,
      (status, learning_id),
    )
  return get_learning(connection, learning_id)


def delete_learning(connection: sqlite3.Connection, learning_id: int) -> None:
  get_learning(connection, learning_id)
  with connection:
    connection.execute("DELETE FROM learnings WHERE id = ?", (learning_id,))


def learning_reference(learning_id: int) -> str:
  return f"L-{learning_id:03d}"


def learning_payload(row: sqlite3.Row) -> dict[str, object]:
  return {
    "id": row["id"],
    "reference": learning_reference(int(row["id"])),
    "scope": row["scope"],
    "scope_key": row["scope_key"],
    "title": row["title"],
    "rule_text": row["rule_text"],
    "rationale": row["rationale"],
    "status": row["status"],
    "source_review_run_id": row["source_review_run_id"],
    "source_finding_id": row["source_finding_id"],
    "created_at": row["created_at"],
    "updated_at": row["updated_at"],
  }


def learning_summary_payload(entry: dict[str, object]) -> dict[str, object]:
  return {
    "reference": entry["reference"],
    "scope": entry["scope"],
    "title": entry["title"],
    "rule_text": entry["rule_text"],
    "rationale": entry["rationale"],
  }


def scope_counts(entries: list[dict[str, object]]) -> dict[str, int]:
  counts = {scope: 0 for scope in LEARNING_SCOPES}
  for entry in entries:
    scope = str(entry["scope"])
    counts[scope] = counts.get(scope, 0) + 1
  return counts


def telemetry_sync_target(settings: TelemetrySettings) -> str:
  if not settings.enabled:
    return "disabled"
  if settings.custom_proxy_url:
    return "custom_proxy"
  return "hosted_relay"


def build_telemetry_batch(settings: TelemetrySettings, rows: list[sqlite3.Row]) -> list[dict[str, object]]:
  batch = []
  for row in rows:
    payload = json.loads(str(row["payload_json"]))
    properties = dict(payload)
    properties["$process_person_profile"] = False
    properties["$insert_id"] = f"skill-bill-outbox-{row['id']}"
    batch.append(
      {
        "event": row["event_name"],
        "distinct_id": settings.install_id,
        "properties": properties,
        "timestamp": row["created_at"],
      }
    )
  return batch


def post_json(url: str, payload: dict[str, object], *, error_context: str) -> None:
  request = urllib.request.Request(
    url=url,
    data=json.dumps(payload).encode("utf-8"),
    headers={
      "Content-Type": "application/json",
      "User-Agent": "skill-bill-telemetry/1.0",
    },
    method="POST",
  )
  try:
    with urllib.request.urlopen(request, timeout=10) as response:
      status_code = getattr(response, "status", response.getcode())
      if status_code < 200 or status_code >= 300:
        raise ValueError(f"{error_context} failed with HTTP {status_code}.")
  except urllib.error.HTTPError as error:
    response_body = error.read().decode("utf-8", errors="replace").strip()
    message = f"{error_context} failed with HTTP {error.code}."
    if response_body:
      message = f"{message} {response_body}"
    raise ValueError(message) from error


def send_proxy_batch(settings: TelemetrySettings, rows: list[sqlite3.Row]) -> str | None:
  if not settings.proxy_url:
    raise ValueError("Telemetry relay URL is not configured.")
  payload = {"batch": build_telemetry_batch(settings, rows)}
  error_context = "Telemetry custom proxy sync" if settings.custom_proxy_url else "Telemetry relay sync"
  post_json(
    settings.proxy_url,
    payload,
    error_context=error_context,
  )
  return None


def sync_telemetry(db_path: Path) -> SyncResult:
  settings = load_telemetry_settings()
  sync_target = telemetry_sync_target(settings)
  remote_configured = bool(settings.proxy_url)
  proxy_configured = bool(settings.custom_proxy_url)
  if not settings.enabled:
    return SyncResult(
      status="disabled",
      synced_events=0,
      pending_events=0,
      config_path=settings.config_path,
      telemetry_enabled=False,
      remote_configured=remote_configured,
      proxy_configured=proxy_configured,
      sync_target=sync_target,
      proxy_url=settings.proxy_url,
      custom_proxy_url=settings.custom_proxy_url,
      message="Telemetry is disabled.",
    )
  connection = ensure_database(db_path)
  try:
    pending_before = pending_telemetry_count(connection)
    if not remote_configured:
      return SyncResult(
        status="unconfigured",
        synced_events=0,
        pending_events=pending_before,
        config_path=settings.config_path,
        telemetry_enabled=True,
        remote_configured=False,
        proxy_configured=False,
        sync_target=sync_target,
        proxy_url=settings.proxy_url,
        custom_proxy_url=settings.custom_proxy_url,
        message="Telemetry relay URL is not configured.",
      )
    if pending_before == 0:
      return SyncResult(
        status="noop",
        synced_events=0,
        pending_events=0,
        config_path=settings.config_path,
        telemetry_enabled=True,
        remote_configured=remote_configured,
        proxy_configured=proxy_configured,
        sync_target=sync_target,
        proxy_url=settings.proxy_url,
        custom_proxy_url=settings.custom_proxy_url,
        message="No pending telemetry events.",
      )

    synced_total = 0
    while True:
      rows = fetch_pending_telemetry_events(connection, limit=settings.batch_size)
      if not rows:
        break
      event_ids = [int(row["id"]) for row in rows]
      try:
        send_proxy_batch(settings, rows)
      except (urllib.error.URLError, OSError, ValueError) as error:
        message = str(error)
        mark_telemetry_failed(connection, event_ids=event_ids, error_message=message)
        pending_after = pending_telemetry_count(connection)
        return SyncResult(
          status="failed",
          synced_events=synced_total,
          pending_events=pending_after,
          config_path=settings.config_path,
          telemetry_enabled=True,
          remote_configured=remote_configured,
          proxy_configured=proxy_configured,
          sync_target=sync_target,
          proxy_url=settings.proxy_url,
          custom_proxy_url=settings.custom_proxy_url,
          message=message,
        )
      mark_telemetry_synced(connection, event_ids)
      synced_total += len(event_ids)

    return SyncResult(
      status="synced",
      synced_events=synced_total,
      pending_events=pending_telemetry_count(connection),
      config_path=settings.config_path,
      telemetry_enabled=True,
      remote_configured=remote_configured,
      proxy_configured=proxy_configured,
      sync_target=sync_target,
      proxy_url=settings.proxy_url,
      custom_proxy_url=settings.custom_proxy_url,
    )
  finally:
    connection.close()


def sync_result_payload(result: SyncResult) -> dict[str, object]:
  payload: dict[str, object] = {
    "config_path": str(result.config_path),
    "telemetry_enabled": result.telemetry_enabled,
    "sync_target": result.sync_target,
    "remote_configured": result.remote_configured,
    "proxy_configured": result.proxy_configured,
    "proxy_url": result.proxy_url,
    "custom_proxy_url": result.custom_proxy_url,
    "sync_status": result.status,
    "synced_events": result.synced_events,
    "pending_events": result.pending_events,
  }
  if result.message is not None:
    payload["message"] = result.message
  return payload


def telemetry_status_payload(db_path: Path) -> dict[str, object]:
  settings = load_telemetry_settings()
  payload: dict[str, object] = {
    "config_path": str(settings.config_path),
    "db_path": str(db_path),
    "telemetry_enabled": settings.enabled,
    "sync_target": telemetry_sync_target(settings),
    "remote_configured": bool(settings.proxy_url),
    "proxy_configured": bool(settings.custom_proxy_url),
    "proxy_url": settings.proxy_url,
    "custom_proxy_url": settings.custom_proxy_url,
    "pending_events": 0,
  }
  if not settings.enabled:
    return payload

  payload["install_id"] = settings.install_id
  payload["batch_size"] = settings.batch_size
  connection = ensure_database(db_path)
  try:
    payload["pending_events"] = pending_telemetry_count(connection)
    latest_error = latest_telemetry_error(connection)
    if latest_error is not None:
      payload["latest_error"] = latest_error
    return payload
  finally:
    connection.close()


def emit(payload: dict[str, object], output_format: str) -> None:
  if output_format == "json":
    print(json.dumps(payload, indent=2, sort_keys=True))
    return

  for key, value in payload.items():
    if value is None:
      continue
    if isinstance(value, (list, dict)):
      print(f"{key}:")
      print(json.dumps(value, indent=2, sort_keys=True))
      continue
    print(f"{key}: {value}")


def print_numbered_findings(review_run_id: str, numbered_findings: list[dict[str, object]]) -> None:
  print(f"review_run_id: {review_run_id}")
  for finding in numbered_findings:
    print(
      f"{finding['number']}. [{finding['finding_id']}] "
      f"{finding['severity']} | {finding['confidence']} | "
      f"{finding['location']} | {finding['description']}"
    )


def print_triage_result(review_run_id: str, decisions: list[TriageDecision]) -> None:
  print(f"review_run_id: {review_run_id}")
  for decision in decisions:
    line = f"{decision.number}. {decision.finding_id} -> {decision.outcome_type}"
    if decision.note:
      line += f" | note: {decision.note}"
    print(line)


def print_learnings(entries: list[dict[str, object]]) -> None:
  if not entries:
    print("No learnings found.")
    return

  for entry in entries:
    scope_label = entry["scope"]
    scope_key = entry["scope_key"]
    if scope_key:
      scope_label = f"{scope_label}:{scope_key}"
    print(f"{entry['reference']}. [{entry['status']}] {scope_label} | {entry['title']}")


def summarize_applied_learnings(entries: list[dict[str, object]]) -> str:
  if not entries:
    return "none"
  return ", ".join(str(entry["reference"]) for entry in entries)


def print_resolved_learnings(
  *,
  repo_scope_key: str | None,
  skill_name: str | None,
  entries: list[dict[str, object]],
) -> None:
  print(f"scope_precedence: {' > '.join(LEARNING_SCOPE_PRECEDENCE)}")
  if repo_scope_key is not None:
    print(f"repo_scope_key: {repo_scope_key}")
  if skill_name is not None:
    print(f"skill_name: {skill_name}")
  print(f"applied_learnings: {summarize_applied_learnings(entries)}")
  if not entries:
    print("No active learnings matched this review context.")
    return

  for entry in entries:
    scope_label = entry["scope"]
    scope_key = entry["scope_key"]
    if scope_key:
      scope_label = f"{scope_label}:{scope_key}"
    print(f"- [{entry['reference']}] {scope_label} | {entry['title']} | {entry['rule_text']}")


def import_review_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  text, source_path = read_input(args.input)
  review = parse_review(text)
  connection = ensure_database(db_path)
  try:
    save_imported_review(connection, review, source_path=source_path)
  finally:
    connection.close()

  emit(
    {
      "db_path": str(db_path),
      "review_run_id": review.review_run_id,
      "review_session_id": review.review_session_id,
      "finding_count": len(review.findings),
      "routed_skill": review.routed_skill,
      "detected_scope": review.detected_scope,
      "detected_stack": review.detected_stack,
      "execution_mode": review.execution_mode,
    },
    args.format,
  )
  auto_sync_telemetry(db_path)
  return 0


def record_feedback_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    record_feedback(
      connection,
      review_run_id=args.run_id,
      finding_ids=args.finding,
      event_type=args.event,
      note=args.note,
    )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  emit(
    {
      "db_path": str(db_path),
      "review_run_id": args.run_id,
      "outcome_type": args.event,
      "recorded_findings": len(args.finding),
    },
    args.format,
  )
  return 0


def triage_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    numbered_findings = fetch_numbered_findings(connection, args.run_id)
    if args.list or not args.decision:
      if args.format == "json":
        emit(
          {
            "db_path": str(db_path),
            "review_run_id": args.run_id,
            "findings": numbered_findings,
          },
          args.format,
        )
      else:
        print_numbered_findings(args.run_id, numbered_findings)
      return 0

    decisions = parse_triage_decisions(args.decision, numbered_findings)
    for decision in decisions:
      record_feedback(
        connection,
        review_run_id=args.run_id,
        finding_ids=[decision.finding_id],
        event_type=decision.outcome_type,
        note=decision.note,
      )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  if args.format == "json":
    emit(
      {
        "db_path": str(db_path),
        "review_run_id": args.run_id,
        "recorded": [
          {
            "number": decision.number,
            "finding_id": decision.finding_id,
            "outcome_type": decision.outcome_type,
            "note": decision.note,
          }
          for decision in decisions
        ],
      },
      args.format,
    )
  else:
    print_triage_result(args.run_id, decisions)
  return 0


def stats_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = stats_payload(connection, args.run_id)
  finally:
    connection.close()

  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_add_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    learning_id = add_learning(
      connection,
      scope=args.scope,
      scope_key=args.scope_key,
      title=args.title,
      rule_text=args.rule,
      rationale=args.reason,
      source_review_run_id=args.from_run,
      source_finding_id=args.from_finding,
    )
    payload = learning_payload(get_learning(connection, learning_id))
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_list_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload_entries = [learning_payload(row) for row in list_learnings(connection, status=args.status)]
  finally:
    connection.close()

  if args.format == "json":
    emit({"db_path": str(db_path), "learnings": payload_entries}, args.format)
  else:
    print_learnings(payload_entries)
  return 0


def learnings_show_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = learning_payload(get_learning(connection, args.id))
  finally:
    connection.close()

  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_resolve_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  telemetry_enabled = telemetry_is_enabled()
  try:
    with connection:
      repo_scope_key, skill_name, rows = resolve_learnings(
        connection,
        repo_scope_key=args.repo,
        skill_name=args.skill,
      )
      payload_entries = [learning_payload(row) for row in rows]
      if args.review_session_id:
        learnings_cache = {
          "skill_name": skill_name,
          "applied_learning_count": len(payload_entries),
          "applied_learning_references": [entry["reference"] for entry in payload_entries],
          "applied_learnings": summarize_applied_learnings(payload_entries),
          "scope_counts": scope_counts(payload_entries),
          "learnings": [learning_summary_payload(entry) for entry in payload_entries],
        }
        save_session_learnings(
          connection,
          review_session_id=args.review_session_id,
          learnings_json=json.dumps(learnings_cache, sort_keys=True),
        )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload = {
    "db_path": str(db_path),
    "repo_scope_key": repo_scope_key,
    "skill_name": skill_name,
    "scope_precedence": list(LEARNING_SCOPE_PRECEDENCE),
    "applied_learnings": summarize_applied_learnings(payload_entries),
    "learnings": payload_entries,
  }
  if args.review_session_id:
    payload["review_session_id"] = args.review_session_id
  if args.format == "json":
    emit(payload, args.format)
  else:
    print_resolved_learnings(
      repo_scope_key=repo_scope_key,
      skill_name=skill_name,
      entries=payload_entries,
    )
  return 0


def learnings_edit_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  if all(
    value is None
    for value in (args.scope, args.scope_key, args.title, args.rule, args.reason)
  ):
    raise ValueError("Learning edit requires at least one field to update.")

  connection = ensure_database(db_path)
  try:
    payload = learning_payload(
      edit_learning(
        connection,
        learning_id=args.id,
        scope=args.scope,
        scope_key=args.scope_key,
        title=args.title,
        rule_text=args.rule,
        rationale=args.reason,
      )
    )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_status_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = learning_payload(
      set_learning_status(connection, learning_id=args.id, status=args.status_value)
    )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_delete_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    delete_learning(connection, args.id)
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  emit(
    {
      "db_path": str(db_path),
      "deleted_learning_id": args.id,
    },
    args.format,
  )
  return 0


def auto_sync_telemetry(db_path: Path) -> SyncResult | None:
  try:
    result = sync_telemetry(db_path)
  except ValueError as error:
    print(f"Telemetry sync skipped: {error}", file=sys.stderr)
    return None
  if result.status == "failed" and result.message:
    print(f"Telemetry sync failed: {result.message}", file=sys.stderr)
  return result


def telemetry_status_command(args: argparse.Namespace) -> int:
  payload = telemetry_status_payload(resolve_db_path(args.db))
  emit(payload, args.format)
  return 0


def telemetry_sync_command(args: argparse.Namespace) -> int:
  result = sync_telemetry(resolve_db_path(args.db))
  emit(sync_result_payload(result), args.format)
  return 1 if result.status == "failed" else 0


def telemetry_toggle_command(args: argparse.Namespace) -> int:
  settings, cleared_events = set_telemetry_enabled(
    args.enabled_value,
    db_path=resolve_db_path(args.db),
  )
  payload = {
    "config_path": str(settings.config_path),
    "telemetry_enabled": settings.enabled,
    "sync_target": telemetry_sync_target(settings),
    "remote_configured": bool(settings.proxy_url),
    "proxy_configured": bool(settings.custom_proxy_url),
    "proxy_url": settings.proxy_url,
    "custom_proxy_url": settings.custom_proxy_url,
    "install_id": settings.install_id,
    "cleared_events": cleared_events,
  }
  emit(payload, args.format)
  return 0


def build_parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
    description="Import Skill Bill review output, triage numbered findings, and manage local review learnings."
  )
  parser.add_argument(
    "--db",
    help=f"Optional SQLite path. Defaults to ${DB_ENVIRONMENT_KEY} or {DEFAULT_DB_PATH}.",
  )
  subparsers = parser.add_subparsers(dest="command", required=True)

  import_parser = subparsers.add_parser(
    "import-review",
    help="Import a review output file or stdin into the local SQLite store.",
  )
  import_parser.add_argument("input", nargs="?", default="-", help="Path to review text, or '-' for stdin.")
  import_parser.add_argument("--format", choices=("text", "json"), default="text")
  import_parser.set_defaults(handler=import_review_command)

  feedback_parser = subparsers.add_parser(
    "record-feedback",
    help="Record explicit feedback events for one or more findings in an imported review run.",
  )
  feedback_parser.add_argument("--run-id", required=True, help="Imported review run id.")
  feedback_parser.add_argument(
    "--event",
    choices=FINDING_OUTCOME_TYPES,
    required=True,
    help="Canonical finding outcome to record.",
  )
  feedback_parser.add_argument(
    "--finding",
    action="append",
    required=True,
    help="Finding id to update. Repeat the flag to record multiple findings.",
  )
  feedback_parser.add_argument("--note", default="", help="Optional note for the recorded feedback event.")
  feedback_parser.add_argument("--format", choices=("text", "json"), default="text")
  feedback_parser.set_defaults(handler=record_feedback_command)

  triage_parser = subparsers.add_parser(
    "triage",
    help="Show numbered findings for a review run and record triage decisions by number.",
  )
  triage_parser.add_argument("--run-id", required=True, help="Imported review run id.")
  triage_parser.add_argument(
    "--decision",
    action="append",
    help="Triage entry like '1 fix', '2 skip - intentional', or '3 accept - good catch'.",
  )
  triage_parser.add_argument("--list", action="store_true", help="Show the numbered findings without recording decisions.")
  triage_parser.add_argument("--format", choices=("text", "json"), default="text")
  triage_parser.set_defaults(handler=triage_command)

  stats_parser = subparsers.add_parser(
    "stats",
    help="Show aggregate or per-run review acceptance metrics from the local SQLite store.",
  )
  stats_parser.add_argument("--run-id", help="Optional review run id to scope stats to one review.")
  stats_parser.add_argument("--format", choices=("text", "json"), default="text")
  stats_parser.set_defaults(handler=stats_command)

  learnings_parser = subparsers.add_parser(
    "learnings",
    help="Manage local review learnings derived from rejected review findings.",
  )
  learnings_subparsers = learnings_parser.add_subparsers(dest="learnings_command", required=True)

  learnings_add_parser = learnings_subparsers.add_parser(
    "add",
    help="Create a learning from a rejected review finding.",
  )
  learnings_add_parser.add_argument("--scope", choices=LEARNING_SCOPES, default="global")
  learnings_add_parser.add_argument("--scope-key", default="")
  learnings_add_parser.add_argument("--title", required=True)
  learnings_add_parser.add_argument("--rule", required=True)
  learnings_add_parser.add_argument("--reason", default="", help="Rationale; auto-populated from the rejection note when omitted.")
  learnings_add_parser.add_argument("--from-run", required=True, help="Source review run id.")
  learnings_add_parser.add_argument("--from-finding", required=True, help="Source finding id.")
  learnings_add_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_add_parser.set_defaults(handler=learnings_add_command)

  learnings_list_parser = learnings_subparsers.add_parser("list", help="List local learning entries.")
  learnings_list_parser.add_argument("--status", choices=("all",) + LEARNING_STATUSES, default="all")
  learnings_list_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_list_parser.set_defaults(handler=learnings_list_command)

  learnings_show_parser = learnings_subparsers.add_parser("show", help="Show a single learning entry.")
  learnings_show_parser.add_argument("--id", type=int, required=True)
  learnings_show_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_show_parser.set_defaults(handler=learnings_show_command)

  learnings_resolve_parser = learnings_subparsers.add_parser(
    "resolve",
    help="Resolve active learnings for a review context using global, repo, and skill scope.",
  )
  learnings_resolve_parser.add_argument("--repo", help="Optional repo scope key to match repo-scoped learnings.")
  learnings_resolve_parser.add_argument("--skill", help="Optional review skill name to match skill-scoped learnings.")
  learnings_resolve_parser.add_argument(
    "--review-session-id",
    help="Review session id for cross-event grouping. Required when telemetry is enabled.",
  )
  learnings_resolve_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_resolve_parser.set_defaults(handler=learnings_resolve_command)

  learnings_edit_parser = learnings_subparsers.add_parser("edit", help="Edit a local learning entry.")
  learnings_edit_parser.add_argument("--id", type=int, required=True)
  learnings_edit_parser.add_argument("--scope", choices=LEARNING_SCOPES)
  learnings_edit_parser.add_argument("--scope-key")
  learnings_edit_parser.add_argument("--title")
  learnings_edit_parser.add_argument("--rule")
  learnings_edit_parser.add_argument("--reason")
  learnings_edit_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_edit_parser.set_defaults(handler=learnings_edit_command)

  learnings_disable_parser = learnings_subparsers.add_parser("disable", help="Disable a learning entry.")
  learnings_disable_parser.add_argument("--id", type=int, required=True)
  learnings_disable_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_disable_parser.set_defaults(handler=learnings_status_command, status_value="disabled")

  learnings_enable_parser = learnings_subparsers.add_parser("enable", help="Enable a disabled learning entry.")
  learnings_enable_parser.add_argument("--id", type=int, required=True)
  learnings_enable_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_enable_parser.set_defaults(handler=learnings_status_command, status_value="active")

  learnings_delete_parser = learnings_subparsers.add_parser("delete", help="Delete a learning entry.")
  learnings_delete_parser.add_argument("--id", type=int, required=True)
  learnings_delete_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_delete_parser.set_defaults(handler=learnings_delete_command)

  telemetry_parser = subparsers.add_parser(
    "telemetry",
    help="Inspect, control, and manually sync remote telemetry.",
  )
  telemetry_subparsers = telemetry_parser.add_subparsers(dest="telemetry_command", required=True)

  telemetry_status_parser = telemetry_subparsers.add_parser("status", help="Show local telemetry configuration and sync status.")
  telemetry_status_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_status_parser.set_defaults(handler=telemetry_status_command)

  telemetry_sync_parser = telemetry_subparsers.add_parser("sync", help="Flush pending telemetry events to the active proxy target.")
  telemetry_sync_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_sync_parser.set_defaults(handler=telemetry_sync_command)

  telemetry_enable_parser = telemetry_subparsers.add_parser("enable", help="Enable remote telemetry sync.")
  telemetry_enable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_enable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=True)

  telemetry_disable_parser = telemetry_subparsers.add_parser("disable", help="Disable remote telemetry sync.")
  telemetry_disable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_disable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=False)

  return parser


def main(argv: list[str] | None = None) -> int:
  parser = build_parser()
  args = parser.parse_args(argv)

  try:
    return int(args.handler(args))
  except ValueError as error:
    print(str(error), file=sys.stderr)
    return 1


if __name__ == "__main__":
  raise SystemExit(main())
