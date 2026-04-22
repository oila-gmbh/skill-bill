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
  feature_verify_finished,
  feature_verify_started,
)


STARTED_PARAMS = {
  "acceptance_criteria_count": 6,
  "rollout_relevant": True,
  "spec_summary": "Verify payment-retry feature meets all 6 acceptance criteria",
}

FINISHED_PARAMS = {
  "feature_flag_audit_performed": True,
  "review_iterations": 2,
  "audit_result": "had_gaps",
  "completion_status": "completed",
  "history_relevance": "high",
  "history_helpfulness": "medium",
  "gaps_found": ["rollout toggle missing in admin UI"],
}


class FeatureVerifyEnabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "anonymous", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": self.config_path,
      "SKILL_BILL_TELEMETRY_ENABLED": "true",
      "SKILL_BILL_INSTALL_ID": "test-install-id",
      # Defensive: pin the relay URL to an unreachable loopback so any
      # accidental telemetry sync during tests cannot ship events to the
      # production PostHog project (#43).
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
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
    result = feature_verify_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["session_id"], r"^fvr-\d{8}-\d{6}-[a-z0-9]{4}$")
    rows = self._outbox_rows("skillbill_feature_verify_started")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["acceptance_criteria_count"], 6)
    self.assertTrue(payload["rollout_relevant"])
    # anonymous level excludes spec_summary
    self.assertNotIn("spec_summary", payload)

  def test_finished_standalone_emits(self) -> None:
    started = feature_verify_started(**STARTED_PARAMS)
    result = feature_verify_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    self.assertEqual(result["status"], "ok")
    rows = self._outbox_rows("skillbill_feature_verify_finished")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["completion_status"], "completed")
    self.assertEqual(payload["audit_result"], "had_gaps")
    self.assertEqual(payload["history_relevance"], "high")
    self.assertEqual(payload["history_helpfulness"], "medium")
    self.assertNotIn("gaps_found", payload)  # full-only
    self.assertNotIn("spec_summary", payload)

  def test_finished_validates_completion_status(self) -> None:
    started = feature_verify_started(**STARTED_PARAMS)
    params = dict(FINISHED_PARAMS)
    params["completion_status"] = "exploded"
    result = feature_verify_finished(session_id=started["session_id"], **params)
    self.assertEqual(result["status"], "error")
    self.assertIn("completion_status", result["error"])

  def test_finished_validates_history_signal_values(self) -> None:
    started = feature_verify_started(**STARTED_PARAMS)
    params = dict(FINISHED_PARAMS)
    params["history_helpfulness"] = "wildly"
    result = feature_verify_finished(session_id=started["session_id"], **params)
    self.assertEqual(result["status"], "error")
    self.assertIn("history_helpfulness", result["error"])

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
      started = feature_verify_started(**STARTED_PARAMS)
      feature_verify_finished(session_id=started["session_id"], **FINISHED_PARAMS)
      started_rows = self._outbox_rows("skillbill_feature_verify_started")
      finished_rows = self._outbox_rows("skillbill_feature_verify_finished")
      started_payload = json.loads(started_rows[0]["payload_json"])
      finished_payload = json.loads(finished_rows[0]["payload_json"])
      self.assertIn("spec_summary", started_payload)
      self.assertIn("gaps_found", finished_payload)
      self.assertEqual(finished_payload["gaps_found"], ["rollout toggle missing in admin UI"])
    finally:
      os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)

  def test_orchestrated_started_is_noop(self) -> None:
    result = feature_verify_started(orchestrated=True, **STARTED_PARAMS)
    self.assertEqual(result["mode"], "orchestrated")
    self.assertEqual(result["status"], "skipped_in_orchestrated_mode")
    rows = self._outbox_rows("skillbill_feature_verify_started")
    self.assertEqual(len(rows), 0)

  def test_orchestrated_finished_returns_payload_and_does_not_emit(self) -> None:
    result = feature_verify_finished(
      orchestrated=True,
      acceptance_criteria_count=6,
      rollout_relevant=True,
      spec_summary="payment-retry",
      duration_seconds=300,
      **FINISHED_PARAMS,
    )
    self.assertEqual(result["mode"], "orchestrated")
    payload = result["telemetry_payload"]
    self.assertEqual(payload["skill"], "bill-feature-verify")
    self.assertEqual(payload["audit_result"], "had_gaps")
    self.assertEqual(payload["duration_seconds"], 300)
    self.assertEqual(payload["history_relevance"], "high")
    self.assertEqual(payload["history_helpfulness"], "medium")
    self.assertNotIn("session_id", payload)
    started_rows = self._outbox_rows("skillbill_feature_verify_started")
    finished_rows = self._outbox_rows("skillbill_feature_verify_finished")
    self.assertEqual(len(started_rows), 0)
    self.assertEqual(len(finished_rows), 0)


class FeatureVerifyDisabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": os.path.join(self.temp_dir, "metrics.db"),
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      # Belt-and-braces: even when telemetry is off in this fixture, pin the
      # relay URL so an accidental enable can never ship to prod (#43).
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
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

  def test_started_skips_when_disabled(self) -> None:
    result = feature_verify_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "skipped")

  def test_finished_skips_when_disabled(self) -> None:
    result = feature_verify_finished(session_id="fvr-dummy", **FINISHED_PARAMS)
    self.assertEqual(result["status"], "skipped")


if __name__ == "__main__":
  unittest.main()
