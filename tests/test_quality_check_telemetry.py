from __future__ import annotations

import json
from pathlib import Path
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.mcp_server import (
  quality_check_finished,
  quality_check_started,
)


STARTED_PARAMS = {
  "routed_skill": "bill-agent-config-quality-check",
  "detected_stack": "agent-config",
  "scope_type": "branch_diff",
  "initial_failure_count": 3,
}

FINISHED_PARAMS = {
  "final_failure_count": 0,
  "iterations": 2,
  "result": "pass",
  "failing_check_names": [],
  "unsupported_reason": "",
}


def _install_test_config(temp_dir: str, *, level: str) -> tuple[str, str]:
  db_path = os.path.join(temp_dir, "metrics.db")
  config_path = os.path.join(temp_dir, "config.json")
  Path(config_path).write_text(
    json.dumps({
      "install_id": "test-install-id",
      "telemetry": {"level": level, "proxy_url": "", "batch_size": 50},
    }),
    encoding="utf-8",
  )
  return db_path, config_path


class QualityCheckEnabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path, self.config_path = _install_test_config(self.temp_dir, level="anonymous")
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": self.config_path,
      "SKILL_BILL_TELEMETRY_ENABLED": "true",
      "SKILL_BILL_INSTALL_ID": "test-install-id",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _outbox_rows(self, event_name: str) -> list[sqlite3.Row]:
    if not os.path.exists(self.db_path):
      return []
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    try:
      rows = conn.execute(
        "SELECT * FROM telemetry_outbox WHERE event_name = ?",
        (event_name,),
      ).fetchall()
    except sqlite3.OperationalError:
      rows = []
    conn.close()
    return rows

  def test_started_standalone_emits(self) -> None:
    result = quality_check_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["session_id"], r"^qck-\d{8}-\d{6}-[a-z0-9]{4}$")
    rows = self._outbox_rows("skillbill_quality_check_started")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["routed_skill"], "bill-agent-config-quality-check")
    self.assertEqual(payload["scope_type"], "branch_diff")

  def test_started_validates_scope_type(self) -> None:
    params = dict(STARTED_PARAMS)
    params["scope_type"] = "galaxy"
    result = quality_check_started(**params)
    self.assertEqual(result["status"], "error")
    self.assertIn("scope_type", result["error"])

  def test_finished_standalone_emits(self) -> None:
    started = quality_check_started(**STARTED_PARAMS)
    result = quality_check_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    self.assertEqual(result["status"], "ok")
    rows = self._outbox_rows("skillbill_quality_check_finished")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["result"], "pass")
    self.assertEqual(payload["iterations"], 2)
    # anonymous level excludes these fields
    self.assertNotIn("failing_check_names", payload)
    self.assertNotIn("unsupported_reason", payload)

  def test_finished_validates_result(self) -> None:
    started = quality_check_started(**STARTED_PARAMS)
    params = dict(FINISHED_PARAMS)
    params["result"] = "exploded"
    result = quality_check_finished(session_id=started["session_id"], **params)
    self.assertEqual(result["status"], "error")
    self.assertIn("result", result["error"])

  def test_full_level_includes_redacted_fields(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    try:
      started = quality_check_started(**STARTED_PARAMS)
      params = dict(FINISHED_PARAMS)
      params["failing_check_names"] = ["detekt:style", "ktlint:format"]
      params["result"] = "fail"
      params["final_failure_count"] = 2
      quality_check_finished(session_id=started["session_id"], **params)
      rows = self._outbox_rows("skillbill_quality_check_finished")
      payload = json.loads(rows[0]["payload_json"])
      self.assertEqual(payload["failing_check_names"], ["detekt:style", "ktlint:format"])
      self.assertEqual(payload["unsupported_reason"], "")
    finally:
      os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)

  def test_orchestrated_started_is_noop(self) -> None:
    result = quality_check_started(orchestrated=True, **STARTED_PARAMS)
    self.assertEqual(result["mode"], "orchestrated")
    self.assertEqual(result["status"], "skipped_in_orchestrated_mode")
    rows = self._outbox_rows("skillbill_quality_check_started")
    self.assertEqual(len(rows), 0)

  def test_orchestrated_finished_returns_payload_and_does_not_emit(self) -> None:
    result = quality_check_finished(
      orchestrated=True,
      routed_skill="bill-agent-config-quality-check",
      detected_stack="agent-config",
      scope_type="branch_diff",
      initial_failure_count=3,
      duration_seconds=41,
      **FINISHED_PARAMS,
    )
    self.assertEqual(result["mode"], "orchestrated")
    payload = result["telemetry_payload"]
    self.assertEqual(payload["skill"], "bill-quality-check")
    self.assertEqual(payload["result"], "pass")
    self.assertEqual(payload["duration_seconds"], 41)
    self.assertNotIn("session_id", payload)
    # Zero outbox rows: hallmark of orchestrated mode.
    started_rows = self._outbox_rows("skillbill_quality_check_started")
    finished_rows = self._outbox_rows("skillbill_quality_check_finished")
    self.assertEqual(len(started_rows), 0)
    self.assertEqual(len(finished_rows), 0)

  def test_orchestrated_full_level_includes_redacted_fields(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    try:
      result = quality_check_finished(
        orchestrated=True,
        routed_skill="bill-agent-config-quality-check",
        detected_stack="agent-config",
        scope_type="branch_diff",
        initial_failure_count=3,
        duration_seconds=10,
        final_failure_count=1,
        iterations=3,
        result="fail",
        failing_check_names=["ktlint:format"],
        unsupported_reason="",
      )
      payload = result["telemetry_payload"]
      self.assertEqual(payload["failing_check_names"], ["ktlint:format"])
    finally:
      os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)


class QualityCheckDisabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": os.path.join(self.temp_dir, "metrics.db"),
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_started_standalone_skips_when_disabled(self) -> None:
    result = quality_check_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "skipped")

  def test_finished_standalone_skips_when_disabled(self) -> None:
    result = quality_check_finished(session_id="qck-dummy", **FINISHED_PARAMS)
    self.assertEqual(result["status"], "skipped")


if __name__ == "__main__":
  unittest.main()
