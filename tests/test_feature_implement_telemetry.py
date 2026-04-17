from __future__ import annotations

import json
from pathlib import Path
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.constants import BOUNDARY_HISTORY_VALUES
from skill_bill.mcp_server import (
  feature_implement_finished,
  feature_implement_started,
)


STARTED_PARAMS = {
  "feature_size": "MEDIUM",
  "acceptance_criteria_count": 5,
  "open_questions_count": 1,
  "spec_input_types": ["markdown_file", "image"],
  "spec_word_count": 2400,
  "rollout_needed": True,
  "feature_name": "payment-retry",
  "issue_key": "ME-1234",
  "issue_key_type": "jira",
  "spec_summary": "Retry failed payments up to 3 times with exponential backoff",
}

FINISHED_PARAMS = {
  "completion_status": "completed",
  "plan_correction_count": 0,
  "plan_task_count": 8,
  "plan_phase_count": 1,
  "feature_flag_used": True,
  "feature_flag_pattern": "legacy",
  "files_created": 3,
  "files_modified": 5,
  "tasks_completed": 8,
  "review_iterations": 2,
  "audit_result": "all_pass",
  "audit_iterations": 1,
  "validation_result": "pass",
  "boundary_history_written": True,
  "boundary_history_value": "medium",
  "pr_created": True,
  "plan_deviation_notes": "Task 5 split into 5a/5b",
}


class FeatureImplementEnabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"enabled": True, "proxy_url": "", "batch_size": 50},
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
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
      "SELECT * FROM telemetry_outbox WHERE event_name = ?",
      (event_name,),
    ).fetchall()
    conn.close()
    return rows

  def _session_row(self, session_id: str) -> sqlite3.Row | None:
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
      "SELECT * FROM feature_implement_sessions WHERE session_id = ?",
      (session_id,),
    ).fetchone()
    conn.close()
    return row

  def test_started_returns_session_id(self) -> None:
    result = feature_implement_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["session_id"], r"^fis-\d{8}-\d{6}-[a-z0-9]{4}$")

  def test_started_enqueues_event(self) -> None:
    result = feature_implement_started(**STARTED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_started")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["session_id"], result["session_id"])

  def test_started_anonymous_payload_excludes_content(self) -> None:
    feature_implement_started(**STARTED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_started")
    payload = json.loads(rows[0]["payload_json"])
    self.assertNotIn("feature_name", payload)
    self.assertNotIn("spec_summary", payload)
    self.assertIn("feature_size", payload)
    self.assertIn("issue_key_provided", payload)
    self.assertTrue(payload["issue_key_provided"])

  def test_started_full_payload_includes_content(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    result = feature_implement_started(**STARTED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_started")
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["feature_name"], "payment-retry")
    self.assertEqual(payload["spec_summary"], "Retry failed payments up to 3 times with exponential backoff")
    os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)

  def test_started_validates_enums(self) -> None:
    params = dict(STARTED_PARAMS)
    params["feature_size"] = "HUGE"
    result = feature_implement_started(**params)
    self.assertEqual(result["status"], "error")
    self.assertIn("feature_size", result["error"])

  def test_finished_updates_session(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    session_id = started["session_id"]
    result = feature_implement_finished(session_id=session_id, **FINISHED_PARAMS)
    self.assertEqual(result["status"], "ok")
    row = self._session_row(session_id)
    self.assertIsNotNone(row)
    self.assertEqual(row["completion_status"], "completed")
    self.assertEqual(row["plan_task_count"], 8)
    self.assertIsNotNone(row["finished_at"])

  def test_finished_enqueues_event(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    feature_implement_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    self.assertEqual(len(rows), 1)

  def test_finished_anonymous_excludes_content(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    feature_implement_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertNotIn("feature_name", payload)
    self.assertNotIn("spec_summary", payload)
    self.assertNotIn("plan_deviation_notes", payload)

  def test_finished_full_includes_content(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    started = feature_implement_started(**STARTED_PARAMS)
    feature_implement_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["feature_name"], "payment-retry")
    self.assertEqual(payload["plan_deviation_notes"], "Task 5 split into 5a/5b")
    os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)

  def test_finished_includes_started_fields(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    feature_implement_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["feature_size"], "MEDIUM")
    self.assertEqual(payload["spec_word_count"], 2400)
    self.assertTrue(payload["issue_key_provided"])
    self.assertEqual(payload["completion_status"], "completed")

  def test_duration_seconds_calculated(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    session_id = started["session_id"]
    conn = sqlite3.connect(self.db_path)
    conn.execute(
      "UPDATE feature_implement_sessions SET started_at = '2026-04-07 10:00:00' WHERE session_id = ?",
      (session_id,),
    )
    conn.commit()
    conn.close()
    feature_implement_finished(session_id=session_id, **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertGreater(payload["duration_seconds"], 0)

  def test_duplicate_started_not_emitted(self) -> None:
    result1 = feature_implement_started(**STARTED_PARAMS)
    session_id = result1["session_id"]
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    conn.execute(
      "UPDATE feature_implement_sessions SET started_event_emitted_at = NULL WHERE session_id = ?",
      (session_id,),
    )
    conn.commit()
    conn.close()
    rows = self._outbox_rows("skillbill_feature_implement_started")
    self.assertEqual(len(rows), 1)

  def test_finished_without_started_still_works(self) -> None:
    result = feature_implement_finished(session_id="fis-00000000-000000-test", **FINISHED_PARAMS)
    self.assertEqual(result["status"], "ok")
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    self.assertEqual(len(rows), 1)

  def test_finished_validates_enums(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    params = dict(FINISHED_PARAMS)
    params["completion_status"] = "invalid"
    result = feature_implement_finished(session_id=started["session_id"], **params)
    self.assertEqual(result["status"], "error")
    self.assertIn("completion_status", result["error"])

  def test_finished_without_child_steps_defaults_to_empty_list(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    feature_implement_finished(session_id=started["session_id"], **FINISHED_PARAMS)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["child_steps"], [])

  def test_finished_embeds_child_steps_in_emitted_event(self) -> None:
    started = feature_implement_started(**STARTED_PARAMS)
    child_steps = [
      {
        "skill": "bill-code-review",
        "total_findings": 5,
        "accepted_findings": 4,
        "rejected_findings": 1,
      },
      {
        "skill": "bill-quality-check",
        "routed_skill": "bill-kotlin-quality-check",
        "result": "pass",
        "iterations": 1,
      },
      {
        "skill": "bill-pr-description",
        "commit_count": 2,
        "pr_created": True,
      },
    ]
    params = dict(FINISHED_PARAMS)
    params["child_steps"] = child_steps
    feature_implement_finished(session_id=started["session_id"], **params)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(len(payload["child_steps"]), 3)
    skills = [step["skill"] for step in payload["child_steps"]]
    self.assertEqual(skills, ["bill-code-review", "bill-quality-check", "bill-pr-description"])
    # Each child_step entry retains the shape the child returned — no mutation.
    self.assertEqual(payload["child_steps"][0]["total_findings"], 5)
    self.assertEqual(payload["child_steps"][1]["result"], "pass")
    self.assertTrue(payload["child_steps"][2]["pr_created"])

  def test_boundary_history_value_round_trips_for_all_enum_values(self) -> None:
    # Every value in BOUNDARY_HISTORY_VALUES must round-trip end-to-end:
    # it lands in the feature_implement_sessions row AND in the emitted
    # skillbill_feature_implement_finished outbox payload unchanged.
    self.assertEqual(
      BOUNDARY_HISTORY_VALUES,
      ("none", "irrelevant", "low", "medium", "high"),
    )
    for value in BOUNDARY_HISTORY_VALUES:
      with self.subTest(boundary_history_value=value):
        started = feature_implement_started(**STARTED_PARAMS)
        session_id = started["session_id"]
        finished_params = dict(FINISHED_PARAMS)
        finished_params["boundary_history_value"] = value
        result = feature_implement_finished(session_id=session_id, **finished_params)
        self.assertEqual(result["status"], "ok")

        row = self._session_row(session_id)
        self.assertIsNotNone(row)
        self.assertEqual(row["boundary_history_value"], value)

        outbox_rows = self._outbox_rows("skillbill_feature_implement_finished")
        matching = [
          json.loads(r["payload_json"])
          for r in outbox_rows
          if json.loads(r["payload_json"]).get("session_id") == session_id
        ]
        self.assertEqual(len(matching), 1)
        self.assertEqual(matching[0]["boundary_history_value"], value)

  def test_child_steps_respect_level_as_built_by_children(self) -> None:
    # The parent aggregates whatever the children returned. Anonymous-level
    # children return anonymous-shape entries; full-level children return
    # full-shape entries. Parent trusts and passes through.
    started = feature_implement_started(**STARTED_PARAMS)
    anonymous_entry = {
      "skill": "bill-quality-check",
      "routed_skill": "bill-kotlin-quality-check",
      "result": "pass",
    }
    full_entry = {
      "skill": "bill-quality-check",
      "routed_skill": "bill-kotlin-quality-check",
      "result": "pass",
      "failing_check_names": [],
      "unsupported_reason": "",
    }
    params = dict(FINISHED_PARAMS)
    params["child_steps"] = [anonymous_entry, full_entry]
    feature_implement_finished(session_id=started["session_id"], **params)
    rows = self._outbox_rows("skillbill_feature_implement_finished")
    payload = json.loads(rows[0]["payload_json"])
    self.assertNotIn("failing_check_names", payload["child_steps"][0])
    self.assertIn("failing_check_names", payload["child_steps"][1])


class FeatureImplementDisabledTest(unittest.TestCase):

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
    result = feature_implement_started(**STARTED_PARAMS)
    self.assertEqual(result["status"], "skipped")
    self.assertRegex(result["session_id"], r"^fis-\d{8}-\d{6}-[a-z0-9]{4}$")

  def test_finished_skips_when_disabled(self) -> None:
    result = feature_implement_finished(session_id="fis-00000000-000000-test", **FINISHED_PARAMS)
    self.assertEqual(result["status"], "skipped")

  def test_no_db_created_when_disabled(self) -> None:
    feature_implement_started(**STARTED_PARAMS)
    db_path = os.path.join(self.temp_dir, "metrics.db")
    self.assertFalse(os.path.exists(db_path))


if __name__ == "__main__":
  unittest.main()
