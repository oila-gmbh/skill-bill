from __future__ import annotations

from pathlib import Path
import json
import os
import sqlite3
import uuid

from skill_bill.constants import (
  CONFIG_ENVIRONMENT_KEY,
  DEFAULT_CONFIG_PATH,
  DEFAULT_TELEMETRY_BATCH_SIZE,
  DEFAULT_TELEMETRY_PROXY_URL,
  INSTALL_ID_ENVIRONMENT_KEY,
  TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY,
  TELEMETRY_ENABLED_ENVIRONMENT_KEY,
  TELEMETRY_LEVEL_ENVIRONMENT_KEY,
  TELEMETRY_LEVELS,
  TELEMETRY_PROXY_URL_ENVIRONMENT_KEY,
  TelemetrySettings,
)


def state_dir() -> Path:
  override = os.environ.get("SKILL_BILL_STATE_DIR")
  if override:
    return Path(override).expanduser().resolve()
  return Path.home() / ".skill-bill"


def resolve_config_path() -> Path:
  candidate = os.environ.get(CONFIG_ENVIRONMENT_KEY)
  if candidate:
    return Path(candidate).expanduser().resolve()
  return DEFAULT_CONFIG_PATH.expanduser().resolve()


def default_local_config() -> dict[str, object]:
  return {
    "install_id": str(uuid.uuid4()),
    "telemetry": {
      "level": "anonymous",
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

  if "level" not in telemetry and "enabled" in telemetry:
    enabled_raw = telemetry.pop("enabled")
    if isinstance(enabled_raw, bool):
      telemetry["level"] = "anonymous" if enabled_raw else "off"
    elif isinstance(enabled_raw, str):
      telemetry["level"] = "anonymous" if parse_bool_value(enabled_raw, name="telemetry.enabled") else "off"
    else:
      telemetry["level"] = "anonymous" if bool(enabled_raw) else "off"
    changed = True
  elif "enabled" in telemetry:
    del telemetry["enabled"]
    changed = True

  for key in ("level", "proxy_url", "batch_size"):
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


def parse_telemetry_level(raw_value: str, *, name: str) -> str:
  normalized = raw_value.strip().lower()
  if normalized in TELEMETRY_LEVELS:
    return normalized
  raise ValueError(f"{name} must be one of: {', '.join(TELEMETRY_LEVELS)}.")


def load_telemetry_settings(*, materialize: bool = False) -> TelemetrySettings:
  config_path = resolve_config_path()
  config = ensure_local_config(config_path) if materialize else read_local_config(config_path)
  level = "off"
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

    level_raw = telemetry.get("level")
    enabled_raw = telemetry.get("enabled")
    if level_raw is not None:
      level = parse_telemetry_level(str(level_raw), name="telemetry.level")
    elif enabled_raw is not None:
      if isinstance(enabled_raw, bool):
        level = "anonymous" if enabled_raw else "off"
      elif isinstance(enabled_raw, str):
        level = "anonymous" if parse_bool_value(enabled_raw, name="telemetry.enabled") else "off"
      else:
        level = "anonymous" if bool(enabled_raw) else "off"
    else:
      level = "anonymous"

    custom_proxy_url = str(telemetry.get("proxy_url", "")).strip()
    batch_size_raw = telemetry.get("batch_size", DEFAULT_TELEMETRY_BATCH_SIZE)
    batch_size = (
      batch_size_raw
      if isinstance(batch_size_raw, int)
      else parse_positive_int(str(batch_size_raw), name="telemetry.batch_size")
    )
    install_id = str(config.get("install_id", "")).strip()

  if os.environ.get(TELEMETRY_LEVEL_ENVIRONMENT_KEY):
    level = parse_telemetry_level(
      os.environ[TELEMETRY_LEVEL_ENVIRONMENT_KEY],
      name=TELEMETRY_LEVEL_ENVIRONMENT_KEY,
    )
  elif os.environ.get(TELEMETRY_ENABLED_ENVIRONMENT_KEY):
    level = (
      "anonymous"
      if parse_bool_value(os.environ[TELEMETRY_ENABLED_ENVIRONMENT_KEY], name=TELEMETRY_ENABLED_ENVIRONMENT_KEY)
      else "off"
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

  enabled = level != "off"
  hosted_relay_url = DEFAULT_TELEMETRY_PROXY_URL.rstrip("/")
  normalized_custom_proxy_url = custom_proxy_url.rstrip("/")
  if normalized_custom_proxy_url == hosted_relay_url:
    normalized_custom_proxy_url = ""
  proxy_url = normalized_custom_proxy_url or hosted_relay_url
  if enabled and not install_id:
    raise ValueError(
      f"Telemetry is enabled but no install_id is configured at '{config_path}'. "
      "Run 'skill-bill telemetry enable' to create one."
    )

  return TelemetrySettings(
    config_path=config_path,
    level=level,
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


def set_telemetry_level(level: str, *, db_path: Path | None = None) -> tuple[TelemetrySettings, int]:
  if level not in TELEMETRY_LEVELS:
    raise ValueError(f"Telemetry level must be one of: {', '.join(TELEMETRY_LEVELS)}.")
  config_path = resolve_config_path()
  if level != "off":
    payload = ensure_local_config(config_path)
    telemetry = payload["telemetry"]
    if not isinstance(telemetry, dict):
      raise ValueError(f"Telemetry config at '{config_path}' must contain a 'telemetry' object.")
    telemetry["level"] = level
    telemetry.pop("enabled", None)
    payload["telemetry"] = telemetry
    config_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return (load_telemetry_settings(materialize=True), 0)

  if config_path.exists():
    config_path.unlink()
  cleared_events = 0 if db_path is None else purge_telemetry_outbox(db_path)
  return (load_telemetry_settings(), cleared_events)


def set_telemetry_enabled(enabled: bool, *, db_path: Path | None = None) -> tuple[TelemetrySettings, int]:
  return set_telemetry_level("anonymous" if enabled else "off", db_path=db_path)
