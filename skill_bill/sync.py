from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request

from skill_bill.config import load_telemetry_settings
from skill_bill.constants import (
  REMOTE_STATS_WORKFLOWS,
  SyncResult,
  TELEMETRY_PROXY_CONTRACT_VERSION,
  TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY,
  TelemetrySettings,
)
from skill_bill.db import ensure_database
from skill_bill.stats import (
  fetch_pending_telemetry_events,
  latest_telemetry_error,
  mark_telemetry_failed,
  mark_telemetry_synced,
  pending_telemetry_count,
)


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
    properties["install_id"] = settings.install_id
    properties["$process_person_profile"] = False
    batch.append(
      {
        "event": row["event_name"],
        "distinct_id": settings.install_id,
        "properties": properties,
        "timestamp": row["created_at"],
      }
    )
  return batch


def _json_request(
  url: str,
  payload: dict[str, object],
  *,
  error_context: str,
  headers: dict[str, str] | None = None,
) -> dict[str, object]:
  request_headers = {
    "Content-Type": "application/json",
    "User-Agent": "skill-bill-telemetry/1.0",
  }
  if headers:
    request_headers.update(headers)
  request = urllib.request.Request(
    url=url,
    data=json.dumps(payload).encode("utf-8"),
    headers=request_headers,
    method="POST",
  )
  try:
    with urllib.request.urlopen(request, timeout=10) as response:
      status_code = getattr(response, "status", response.getcode())
      body = response.read().decode("utf-8", errors="replace").strip()
      if status_code < 200 or status_code >= 300:
        raise ValueError(f"{error_context} failed with HTTP {status_code}.")
  except urllib.error.HTTPError as error:
    response_body = error.read().decode("utf-8", errors="replace").strip()
    message = f"{error_context} failed with HTTP {error.code}."
    if response_body:
      message = f"{message} {response_body}"
    raise ValueError(message) from error
  except urllib.error.URLError as error:
    raise

  if not body:
    return {}
  try:
    decoded = json.loads(body)
  except json.JSONDecodeError as error:
    raise ValueError(f"{error_context} returned invalid JSON.") from error
  if not isinstance(decoded, dict):
    raise ValueError(f"{error_context} returned a non-object JSON payload.")
  return decoded


def _json_get(
  url: str,
  *,
  error_context: str,
  headers: dict[str, str] | None = None,
) -> dict[str, object]:
  request_headers = {
    "User-Agent": "skill-bill-telemetry/1.0",
  }
  if headers:
    request_headers.update(headers)
  request = urllib.request.Request(
    url=url,
    headers=request_headers,
    method="GET",
  )
  try:
    with urllib.request.urlopen(request, timeout=10) as response:
      status_code = getattr(response, "status", response.getcode())
      body = response.read().decode("utf-8", errors="replace").strip()
      if status_code < 200 or status_code >= 300:
        raise ValueError(f"{error_context} failed with HTTP {status_code}.")
  except urllib.error.HTTPError as error:
    response_body = error.read().decode("utf-8", errors="replace").strip()
    message = f"{error_context} failed with HTTP {error.code}."
    if response_body:
      message = f"{message} {response_body}"
    raise ValueError(message) from error
  except urllib.error.URLError:
    raise

  if not body:
    return {}
  try:
    decoded = json.loads(body)
  except json.JSONDecodeError as error:
    raise ValueError(f"{error_context} returned invalid JSON.") from error
  if not isinstance(decoded, dict):
    raise ValueError(f"{error_context} returned a non-object JSON payload.")
  return decoded


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


def _proxy_auth_headers() -> dict[str, str]:
  stats_token = os.environ.get(TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY, "").strip()
  headers: dict[str, str] = {}
  if stats_token:
    headers["Authorization"] = f"Bearer {stats_token}"
  return headers


def _default_proxy_capabilities(proxy_url: str, capabilities_url: str) -> dict[str, object]:
  return {
    "contract_version": "0",
    "source": "remote_proxy",
    "proxy_url": proxy_url,
    "capabilities_url": capabilities_url,
    "supports_ingest": True,
    "supports_stats": False,
    "supported_workflows": [],
  }


def fetch_proxy_capabilities() -> dict[str, object]:
  settings = load_telemetry_settings()
  if not settings.proxy_url:
    raise ValueError("Telemetry relay URL is not configured.")

  capabilities_url = settings.proxy_url.rstrip("/") + "/capabilities"
  headers = _proxy_auth_headers()
  try:
    payload = _json_get(
      capabilities_url,
      error_context="Telemetry proxy capabilities request",
      headers=headers,
    )
  except ValueError as error:
    if "HTTP 404" in str(error) or "HTTP 405" in str(error):
      return _default_proxy_capabilities(settings.proxy_url, capabilities_url)
    raise

  payload.setdefault("contract_version", TELEMETRY_PROXY_CONTRACT_VERSION)
  payload.setdefault("source", "remote_proxy")
  payload.setdefault("proxy_url", settings.proxy_url)
  payload.setdefault("capabilities_url", capabilities_url)
  payload.setdefault("supports_ingest", True)
  payload.setdefault("supports_stats", False)
  payload.setdefault("supported_workflows", [])
  return payload


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


def parse_remote_stats_window(
  *,
  since: str = "",
  date_from: str = "",
  date_to: str = "",
  today: date | None = None,
) -> tuple[str, str]:
  if date_from and since:
    raise ValueError("Use either since or date_from/date_to, not both.")

  today_value = today or datetime.now(timezone.utc).date()
  if date_to:
    try:
      end_date = date.fromisoformat(date_to)
    except ValueError as error:
      raise ValueError("date_to must use YYYY-MM-DD format.") from error
  else:
    end_date = today_value

  if date_from:
    try:
      start_date = date.fromisoformat(date_from)
    except ValueError as error:
      raise ValueError("date_from must use YYYY-MM-DD format.") from error
  else:
    normalized = (since or "30d").strip().lower()
    if not normalized.endswith("d"):
      raise ValueError("since must use <days>d format, for example 7d or 30d.")
    try:
      days = int(normalized[:-1])
    except ValueError as error:
      raise ValueError("since must use <days>d format, for example 7d or 30d.") from error
    if days <= 0:
      raise ValueError("since must be greater than zero days.")
    start_date = end_date - timedelta(days=days - 1)

  if start_date > end_date:
    raise ValueError("date_from must be on or before date_to.")
  return (start_date.isoformat(), end_date.isoformat())


def fetch_remote_stats(
  *,
  workflow: str,
  since: str = "",
  date_from: str = "",
  date_to: str = "",
  group_by: str = "",
) -> dict[str, object]:
  if workflow not in REMOTE_STATS_WORKFLOWS:
    raise ValueError(
      f"workflow must be one of: {', '.join(REMOTE_STATS_WORKFLOWS)}."
    )
  if group_by and group_by not in ("day", "week"):
    raise ValueError("group_by must be one of: day, week.")
  settings = load_telemetry_settings()
  if not settings.proxy_url:
    raise ValueError("Telemetry relay URL is not configured.")

  resolved_date_from, resolved_date_to = parse_remote_stats_window(
    since=since,
    date_from=date_from,
    date_to=date_to,
  )
  capabilities = fetch_proxy_capabilities()
  supports_stats = bool(capabilities.get("supports_stats"))
  supported_workflows_raw = capabilities.get("supported_workflows", [])
  supported_workflows = [
    str(value)
    for value in supported_workflows_raw
    if isinstance(value, str) and value
  ]
  if not supports_stats:
    raise ValueError(
      "Configured telemetry proxy does not support remote stats yet. "
      f"Capabilities URL: {capabilities.get('capabilities_url', settings.proxy_url.rstrip('/') + '/capabilities')}"
    )
  if supported_workflows and workflow not in supported_workflows:
    raise ValueError(
      f"Configured telemetry proxy does not support workflow '{workflow}'. "
      f"Supported workflows: {', '.join(supported_workflows)}."
    )
  stats_url = settings.proxy_url.rstrip("/") + "/stats"
  headers = _proxy_auth_headers()
  request_payload: dict[str, object] = {
    "workflow": workflow,
    "date_from": resolved_date_from,
    "date_to": resolved_date_to,
  }
  if group_by:
    request_payload["group_by"] = group_by
  payload = _json_request(
    stats_url,
    request_payload,
    error_context="Remote telemetry stats request",
    headers=headers,
  )
  payload.setdefault("workflow", workflow)
  payload.setdefault("date_from", resolved_date_from)
  payload.setdefault("date_to", resolved_date_to)
  payload.setdefault("source", "remote_proxy")
  payload.setdefault("stats_url", stats_url)
  payload.setdefault("capabilities", capabilities)
  if group_by:
    payload.setdefault("group_by", group_by)
  return payload


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
      telemetry_level=settings.level,
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
        telemetry_level=settings.level,
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
        telemetry_level=settings.level,
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
          telemetry_level=settings.level,
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
      telemetry_level=settings.level,
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
    "telemetry_level": result.telemetry_level,
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
    "telemetry_level": settings.level,
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


def auto_sync_telemetry(
  db_path: Path,
  *,
  report_failures: bool = False,
) -> SyncResult | None:
  try:
    result = sync_telemetry(db_path)
  except ValueError as error:
    if report_failures:
      print(f"Telemetry sync skipped: {error}", file=sys.stderr)
    return None
  if report_failures and result.status == "failed" and result.message:
    print(f"Telemetry sync failed: {result.message}", file=sys.stderr)
  return result
