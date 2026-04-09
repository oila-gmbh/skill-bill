from __future__ import annotations

from pathlib import Path
import json
import sqlite3
import sys
import urllib.error
import urllib.request

from skill_bill.config import load_telemetry_settings
from skill_bill.constants import SyncResult, TelemetrySettings
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


def auto_sync_telemetry(db_path: Path) -> SyncResult | None:
  try:
    result = sync_telemetry(db_path)
  except ValueError as error:
    print(f"Telemetry sync skipped: {error}", file=sys.stderr)
    return None
  if result.status == "failed" and result.message:
    print(f"Telemetry sync failed: {result.message}", file=sys.stderr)
  return result
